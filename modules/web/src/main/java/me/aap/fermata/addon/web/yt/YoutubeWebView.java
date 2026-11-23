package me.aap.fermata.addon.web.yt;

import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_ERR;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_EVENT;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_FULL_SCREEN;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_ENDED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_FOUND;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PAUSED;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PLAYING;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_PROGRESS;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_TV_DIAG;
import static me.aap.fermata.addon.web.yt.YoutubeJsInterface.JS_VIDEO_QUALITIES;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONTokener;

import java.util.List;

import me.aap.fermata.BuildConfig;
import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.FermataJsInterface;
import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.addon.web.WebBrowserAddon;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.CarInputSink;
import me.aap.fermata.ui.activity.KnobLog;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.activity.TvTransport;
import me.aap.utils.ui.view.NavBarView;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.UiUtils;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeWebView extends FermataWebView implements CarInputSink {
	// VacuumTube's short Cobalt string. It was the one that kept youtube.com/tv on the leanback UI.
	private static final String TV_UA =
			"Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible;";
	static final String TV_HOME = "https://www.youtube.com/tv";
	static final String WEB_HOME = "https://m.youtube.com";
	private boolean tvFallback;
	private boolean playerOpen;
	private boolean injecting;
	private long lastMotionAt;
	private long lastBackAt;
	private int lastTvKey;
	private boolean mainFrameFailed;
	private boolean allowFocusLeave;
	private int desktopRedirects;
	private TvHost tvHost;
	private ViewTreeObserver.OnGlobalFocusChangeListener focusLogger;

	interface TvHost {
		boolean isOpen();

		void showMenu();

		void closeMenu();

		void onLoadStart();

		void onLoaded();

		void onLoadError(String description);
	}
	private static final String CLEAR_HIGHEST_VIDEO_QUALITY_JS =
			"function clearFermataQ() {\n" +
					"  if (!window.__fermataQ) return;\n" +
					"  if (window.__fermataQ.timeout) clearTimeout(window.__fermataQ.timeout);\n" +
					"  if (window.__fermataQ.player && window.__fermataQ.handler) {\n" +
					"    try { window.__fermataQ.player.removeEventListener('onStateChange', window.__fermataQ.handler); } catch(e) {}\n" +
					"  }\n" +
					"  window.__fermataQ = null;\n" +
					"}\n";
	private YoutubeJsInterface js;
	// Reported by the page: the bare video, which has no YouTube controls, is what's in full screen
	boolean videoFullScreen;
	// The video this page is reopening, set as it was once it starts playing
	@Nullable
	private Video reopening;

	// left: when it stopped playing, as SystemClock.elapsedRealtime(), or now if it's playing
	record Video(String url, long position, boolean playing, long left) {
		Video(String url, long position, boolean playing) {
			this(url, position, playing, SystemClock.elapsedRealtime());
		}
	}

	public YoutubeWebView(Context context) {
		super(context);
	}

	public YoutubeWebView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public YoutubeWebView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected FermataJsInterface createJsInterface() {
		MainActivityDelegate a = MainActivityDelegate.get(getContext());
		return js = new YoutubeJsInterface(this, new YoutubeMediaEngine(this, a));
	}

	@Override
	public YoutubeAddon getAddon() {
		return (YoutubeAddon) super.getAddon();
	}

	@Override
	public void init(WebBrowserAddon addon, me.aap.fermata.addon.web.FermataWebClient webClient,
									 FermataChromeClient chromeClient) {
		super.init(addon, webClient, chromeClient);
		MainActivityPrefs.get().addBroadcastListener(this);
		prepareMode();
		focusLogger = (oldFocus, newFocus) -> {
			if (!tvActive()) return;
			KnobLog.note("focus " + viewName(oldFocus) + " -> " + viewName(newFocus));
			if (newFocus == this) allowFocusLeave = false;
			if (allowFocusLeave || ((tvHost != null) && tvHost.isOpen()) || inOverlay(newFocus)) return;
			if (newFocus == null) {
				if (hasWindowFocus()) post(this::requestFocus);
				return;
			}
			if (!isOurFocus(newFocus)) post(this::requestFocus);
		};
		addFocusLogger();
	}

	void setTvHost(TvHost host) {
		tvHost = host;
	}

	void releaseTv() {
		TvTransport.set(null);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		addFocusLogger();
	}

	private void addFocusLogger() {
		if ((focusLogger == null) || !isAttachedToWindow()) return;
		ViewTreeObserver obs = getRootView().getViewTreeObserver();
		obs.removeOnGlobalFocusChangeListener(focusLogger);
		obs.addOnGlobalFocusChangeListener(focusLogger);
	}

	@Override
	protected void onDetachedFromWindow() {
		View root = getRootView();
		if ((root != null) && (focusLogger != null)) {
			root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusLogger);
		}
		super.onDetachedFromWindow();
	}

	void allowFocusLeave() {
		allowFocusLeave = true;
	}

	static boolean tvSelected() {
		return MainActivityPrefs.get().getIntPref(MainActivityPrefs.YT_UI_MODE) ==
				MainActivityPrefs.YT_MODE_TV;
	}

	static String homeUrl() {
		return tvSelected() ? TV_HOME : WEB_HOME;
	}

	public boolean tvActive() {
		return tvSelected() && !tvFallback;
	}

	private void prepareMode() {
		WebSettings settings = getSettings();
		if (tvActive()) settings.setUserAgentString(TV_UA);
		else settings.setUserAgentString(null);
		KnobLog.session(tvActive() ? "tv" : (tvFallback ? "web-fallback" : "web"));
		TvTransport.set(tvActive() ? new TvTransport.Target() {
			@Override
			public boolean play() {
				return tvPlay(true);
			}

			@Override
			public boolean pause() {
				return tvPlay(false);
			}

			@Override
			public boolean toggle() {
				return tvToggle();
			}

			@Override
			public boolean seek(int seconds) {
				return tvSeek(seconds);
			}
		} : null);
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<PreferenceStore.Pref<?>> prefs) {
		super.onPreferenceChanged(store, prefs);
		if (prefs.contains(MainActivityPrefs.YT_UI_MODE)) {
			tvFallback = false;
			prepareMode();
			if (isAttachedToWindow()) loadUrl(homeUrl());
		}

		if (!tvActive() && (getAddon() != null) && getAddon().autoHighestQualityChanged(prefs)) {
			if (getAddon().autoHighestQuality()) setHighestVideoQuality();
			else clearHighestVideoQuality();
		}

		if (!tvActive() && YoutubeSponsorBlock.isPreferenceChanged(prefs)) injectSponsorBlock();
	}

	@Override
	public void loadUrl(@NonNull String url) {
		Log.d("Loading URL: " + url);
		if (tvActive() && (tvHost != null) && TV_HOME.equals(url)) tvHost.onLoadStart();
		super.loadUrl(url);
	}

	void reloadTv() {
		desktopRedirects = 0;
		mainFrameFailed = false;
		loadUrl(TV_HOME);
	}

	@Override
	public void goBack() {
		MediaSessionCallback cb = MainActivityDelegate.get(getContext()).getMediaSessionCallback();
		if (cb.getEngine() instanceof YoutubeMediaEngine) cb.onStop();
		super.goBack();
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		// The page is gone with its activity (e.g. Fermata Auto exited from Android Auto), and nothing
		// can continue its playback. Switching to another section only detaches it until it's shown again.
		if (e == ACTIVITY_DESTROY) {
			MainActivityPrefs.get().removeBroadcastListener(this);
			TvTransport.set(null);
			if (js != null) js.pageRemoved();
		}
		super.onActivityEvent(a, e);
	}

	/**
	 * A car screen has no saved state to restore its page from, as a phone screen does, and a car that's
	 * switched off doesn't always close it. When a new one opens while the previous one's page is still
	 * there with a video, take it over, as "Resume last video" would, within its time limit. The previous
	 * page is stopped either way.
	 */
	@Nullable
	static Video takeOverVideo(MediaSessionCallback cb, YoutubeWebView page) {
		if (!(cb.getEngine() instanceof YoutubeMediaEngine eng) || (eng.getWebView() == page)) return null;
		Video v = eng.getWebView().getVideo(cb);
		if (v == null) return null;
		cb.onStop();
		return page.getAddon().isRecentInCar(v.left()) ? v : null;
	}

	// The video playing or paused on this page
	@Nullable
	private Video getVideo(MediaSessionCallback cb) {
		if (!(cb.getEngine() instanceof YoutubeMediaEngine eng) || (eng.getWebView() != this)) return null;
		String url = getUrl();
		if (!YoutubeUrls.isVideoUrl(url)) return null;
		PlaybackStateCompat st = cb.getPlaybackState();
		int state = st.getState();
		boolean playing = (state == PlaybackStateCompat.STATE_PLAYING);
		if (!playing && (state != PlaybackStateCompat.STATE_PAUSED)) return null;
		long now = SystemClock.elapsedRealtime();
		long pos = st.getPosition();
		if (!playing) return new Video(url, pos, false, st.getLastPositionUpdateTime());
		pos += (long) ((now - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
		return new Video(url, pos, true, now);
	}

	/**
	 * Open a video at its position, playing or paused. It's set as it was when it starts playing rather
	 * than after a delay, as the page may take a while to load, e.g. in a car that's just been started.
	 */
	void reopen(Video v) {
		String url = v.url();
		if (YoutubeUrls.isVideoUrl(url)) {
			reopening = v;
			url = YoutubeUrls.withStartTime(url, v.position());
		}
		loadUrl(url);
	}

	// Called when this page's video starts playing
	boolean isReopeningPaused() {
		return (reopening != null) && !reopening.playing();
	}

	void videoPlaying() {
		Video v = reopening;
		if (v == null) return;
		reopening = null;
		// Pausing from the page, its pause event then updates the session as any other pause does.
		// YouTube may also start a video that the page opens by itself muted: unmute it, or it would
		// play muted, without taking the audio focus, as soon as it's resumed.
		loadUrl("javascript:var v = document.querySelector('video');\n" +
				(v.playing() ? "" : "if (v != null) v.pause();\n") +
				"var p = document.getElementById('movie_player');\n" +
				"try { if ((p != null) && p.unMute) p.unMute(); } catch (e) {}\n" +
				"if (v != null) v.muted = false;");
	}

	@Override
	protected void urlChanged(String url) {
		KnobLog.note("url " + url);
		notePlayer(url, false);
		if (YoutubeUrls.isVideoUrl(url)) {
			// Another video opened before the reopened one started
			if ((reopening != null) &&
					!YoutubeUrls.videoId(url).equals(YoutubeUrls.videoId(reopening.url()))) reopening = null;
			return;
		}
		// Leaving the video within the page (e.g. YouTube's own back button) leaves it like goBack(),
		// even though YouTube keeps playing it as a muted preview
		MediaSessionCallback cb = MainActivityDelegate.get(getContext()).getMediaSessionCallback();
		if ((cb.getEngine() instanceof YoutubeMediaEngine eng) && (eng.getWebView() == this)) cb.onStop();
	}

	@Override
	protected void pageLoaded(String uri) {
		if (mainFrameFailed) {
			mainFrameFailed = false;
			return;
		}
		if (leaveTv(uri, "load")) return;
		if (tvActive()) {
			desktopRedirects = 0;
			installTvDiag();
			notePlayer(uri, true);
			if (tvHost != null) tvHost.onLoaded();
			allowFocusLeave = false;
			requestFocus();
			CookieManager.getInstance().flush();
			return;
		}
		attachListeners();
		injectSponsorBlock();
		addFocusHighlight();
		installKnobJs();
		CookieManager.getInstance().flush();
	}

	void onTvLoadError(String description) {
		if (!tvSelected() || tvFallback) return;
		mainFrameFailed = true;
		KnobLog.note("tv load error: " + description + " url=" + getUrl());
		if (tvHost != null) tvHost.onLoadError(description);
	}

	private boolean leaveTv(String url, String why) {
		if (!tvSelected() || tvFallback || tvUrlOk(url)) return false;
		if (desktopRedirects < 3) {
			desktopRedirects++;
			KnobLog.note("tv reload /tv (" + why + " #" + desktopRedirects + "): " + url);
			post(() -> loadUrl(TV_HOME));
			return true;
		}
		tvFallback = true;
		KnobLog.note("tv fallback (" + why + "): " + url);
		prepareMode();
		Context ctx = getContext();
		UiUtils.showInfo(ctx, ctx.getString(me.aap.fermata.R.string.yt_tv_fallback));
		post(() -> loadUrl(WEB_HOME));
		return true;
	}

	static boolean tvUrlOk(String url) {
		if ((url == null) || url.isEmpty() || url.startsWith("about:blank")) return true;
		Uri uri = Uri.parse(url);
		String host = uri.getHost();
		if (host == null) return true;
		host = host.toLowerCase(java.util.Locale.US);
		if (host.equals("accounts.google.com") || host.equals("google.com") || host.endsWith(".google.com"))
			return true;
		if (host.endsWith("gstatic.com") || host.endsWith("googleapis.com") || host.endsWith("ggpht.com"))
			return true;
		if (host.equals("yt.be") || host.equals("youtu.be")) return true;
		if (host.startsWith("consent.")) return true;
		if (host.endsWith("youtube.com") && !host.endsWith("tv.youtube.com")) {
			if (host.startsWith("m.")) return false;
			String path = uri.getPath();
			if (path == null) return false;
			return path.equals("/tv") || path.startsWith("/tv/") || path.startsWith("/activate") ||
					path.startsWith("/signin");
		}
		return false;
	}

	private boolean atTvRoot() {
		String url = getUrl();
		if ((url == null) || !tvUrlOk(url)) return false;
		Uri uri = Uri.parse(url);
		String path = uri.getPath();
		if ((path == null) || !(path.equals("/tv") || path.startsWith("/tv/"))) return false;
		String fragment = uri.getFragment();
		return (fragment == null) || fragment.isEmpty() || fragment.equals("/") || fragment.equals("/surface");
	}

	static boolean holdsFocus(KeyEvent event) {
		return isTvInput(event.getKeyCode());
	}

	static String describeKey(KeyEvent event, boolean consumed, String by) {
		return "key " + KeyEvent.keyCodeToString(event.getKeyCode()) +
				" action=" + event.getAction() + " dev=" + event.getDeviceId() +
				" source=0x" + Integer.toHexString(event.getSource()) +
				" repeat=" + event.getRepeatCount() +
				" consumed=" + consumed + " by=" + by;
	}

	private void sendKey(int code) {
		if (!hasFocus()) requestFocus();
		long time = SystemClock.uptimeMillis();
		injecting = true;
		try {
			super.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0));
			super.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0));
		} finally {
			injecting = false;
		}
	}

	private boolean clockwise(boolean upOrNegative) {
		boolean invert = MainActivityPrefs.get().getBooleanPref(MainActivityPrefs.YT_KNOB_INVERT);
		// DHU/MHS2 rotate-right arrives as DPAD_UP (the page then moves up). Default is clockwise = next.
		return invert ? !upOrNegative : upOrNegative;
	}

	private int rotationKey(boolean forward) {
		boolean horizontal = MainActivityPrefs.get().getIntPref(MainActivityPrefs.YT_KNOB_AXIS) ==
				MainActivityPrefs.YT_KNOB_HORIZONTAL;
		if (horizontal) return forward ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
		return forward ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
	}

	boolean menuOpen() {
		return (tvHost != null) && tvHost.isOpen();
	}

	private boolean onTvKey(KeyEvent event) {
		int code = event.getKeyCode();
		int stamp = code ^ (event.getAction() << 16) ^ (int) event.getEventTime();
		if (stamp == lastTvKey) return true;
		lastTvKey = stamp;
		if ((tvHost != null) && tvHost.isOpen()) {
			if ((code == KeyEvent.KEYCODE_BACK) && (event.getAction() == KeyEvent.ACTION_DOWN))
				tvHost.closeMenu();
			return true;
		}
		if (isMediaKey(code)) return onMediaKey(event);
		if (!isKnobKey(code) && (code != KeyEvent.KEYCODE_BACK)) return false;
		if (getRootView().findFocus() instanceof EditText) return false;
		if (code == KeyEvent.KEYCODE_BACK) return onTvBack(event);
		if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
		if (playerOpen && ((code == KeyEvent.KEYCODE_DPAD_UP) || (code == KeyEvent.KEYCODE_DPAD_DOWN))) {
			tvSeek(clockwise(code == KeyEvent.KEYCODE_DPAD_UP) ? 10 : -10);
			return true;
		}
		switch (code) {
			case KeyEvent.KEYCODE_DPAD_DOWN -> sendKey(rotationKey(clockwise(false)));
			case KeyEvent.KEYCODE_DPAD_UP -> sendKey(rotationKey(clockwise(true)));
			case KeyEvent.KEYCODE_DPAD_LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
			case KeyEvent.KEYCODE_DPAD_RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
			case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				if (playerOpen) tvToggle();
				else sendKey(KeyEvent.KEYCODE_ENTER);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private boolean onTvBack(KeyEvent event) {
		if (event.getEventTime() == lastBackAt) return true;
		if (event.getAction() == KeyEvent.ACTION_DOWN) lastBackAt = event.getEventTime();
		if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
		if ((tvHost != null) && tvHost.isOpen()) {
			tvHost.closeMenu();
			return true;
		}
		if (playerOpen || !atTvRoot()) {
			sendKey(KeyEvent.KEYCODE_ESCAPE);
			return true;
		}
		if (tvHost != null) tvHost.showMenu();
		else sendKey(KeyEvent.KEYCODE_ESCAPE);
		return true;
	}

	private boolean onMediaKey(KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
		return switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_MEDIA_PLAY -> tvPlay(true);
			case KeyEvent.KEYCODE_MEDIA_PAUSE -> tvPlay(false);
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> tvToggle();
			case KeyEvent.KEYCODE_MEDIA_NEXT -> tvSeek(10);
			case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tvSeek(-10);
			default -> false;
		};
	}

	private boolean tvToggle() {
		evaluateJavascript(
				"(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()",
				null);
		return true;
	}

	private boolean tvPlay(boolean play) {
		evaluateJavascript(
				"(function(){var v=document.querySelector('video');if(!v)return;if(" + play +
						")v.play();else v.pause();})()",
				null);
		return true;
	}

	private boolean tvSeek(int seconds) {
		evaluateJavascript(
				"(function(){var v=document.querySelector('video');if(!v)return;var n=Math.max(0,(v.currentTime||0)+(" +
						seconds + "));try{v.currentTime=n;}catch(e){}})()",
				null);
		KnobLog.note("seek " + seconds);
		return true;
	}

	void onTvState(String data) {
		boolean open = (data != null) && data.contains("player=1");
		if (open == playerOpen) return;
		playerOpen = open;
		KnobLog.note("player " + (open ? "open" : "closed") + " " + data);
	}

	private void notePlayer(String url, boolean log) {
		boolean open = (url != null) && (url.contains("watch"));
		if (open == playerOpen) return;
		playerOpen = open;
		if (log) KnobLog.note("player " + (open ? "open" : "closed") + " url");
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (injecting || !tvActive()) return super.dispatchKeyEvent(event);
		boolean consumed = onTvKey(event);
		String by = ((tvHost != null) && tvHost.isOpen()) ? "menu" : (consumed ? "tv" : "pass");
		if (!consumed) consumed = super.dispatchKeyEvent(event);
		KnobLog.note(describeKey(event, consumed, by));
		return consumed || holdsFocus(event);
	}

	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		if (tvActive() && consumeTvMotion(event)) return true;
		return super.dispatchGenericMotionEvent(event);
	}

	boolean consumeTvMotion(MotionEvent event) {
		if (!tvActive()) return false;
		if (event.getEventTime() == lastMotionAt) return true;
		lastMotionAt = event.getEventTime();
		if ((event.getAction() != MotionEvent.ACTION_SCROLL) ||
				!(event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER) ||
						(event.getAxisValue(MotionEvent.AXIS_SCROLL) != 0f) ||
						(event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f))) return false;
		float v = event.getAxisValue(MotionEvent.AXIS_SCROLL);
		if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
		if (v == 0f) return false;
		boolean consumed = true;
		if ((tvHost != null) && tvHost.isOpen()) {
			moveMenuFocus(clockwise(v < 0f));
		} else if (playerOpen) {
			tvSeek(clockwise(v < 0f) ? 10 : -10);
		} else {
			sendKey(rotationKey(clockwise(v < 0f)));
		}
		KnobLog.note("motion scroll=" + v + " consumed=" + consumed + " by=tv");
		return true;
	}

	private void moveMenuFocus(boolean forward) {
		View focus = getRootView().findFocus();
		if ((focus == null) || (focus == this)) {
			if (tvHost != null) tvHost.showMenu();
			return;
		}
		View next = focus.focusSearch(forward ? View.FOCUS_DOWN : View.FOCUS_UP);
		if (next != null) next.requestFocus();
		else {
			allowFocusLeave = true;
			focus.clearFocus();
			KnobLog.note("focus released toward AA rail");
		}
	}

	private static boolean isTvInput(int code) {
		return isKnobKey(code) || isMediaKey(code) || (code == KeyEvent.KEYCODE_BACK) ||
				(code == KeyEvent.KEYCODE_ESCAPE);
	}

	private static boolean isMediaKey(int code) {
		return switch (code) {
			case KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
					KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
					KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true;
			default -> false;
		};
	}

	private boolean isOurFocus(View focus) {
		for (View v = focus; v != null; v = (v.getParent() instanceof View p) ? p : null) {
			if (v == this) return true;
		}
		return false;
	}

	private static boolean inOverlay(View focus) {
		for (View v = focus; v != null; v = (v.getParent() instanceof View p) ? p : null) {
			if (v instanceof YoutubeTvOverlay) return true;
		}
		return false;
	}

	private static String viewName(View view) {
		if (view == null) return "none";
		String id = "";
		try {
			if (view.getId() != View.NO_ID) id = "#" + view.getResources().getResourceEntryName(view.getId());
		} catch (Exception ignore) {
		}
		return view.getClass().getSimpleName() + id;
	}

	@Override
	protected void onKeyNavigation() {
		if (tvActive()) return;
		super.onKeyNavigation();
	}

	private void installTvDiag() {
		evaluateJavascript(
				"if (!window.__fermataTvDiag) {\n" +
						"window.__fermataTvDiag = true;\n" +
						"function fermataTvReport(v, why) {\n" +
						"  " + JS_EVENT + "(" + JS_TV_DIAG + ", why + ' ready=' + v.readyState + ' t=' + Math.floor(v.currentTime) + ' paused=' + v.paused + ' ' + location.href);\n" +
						"}\n" +
						"function fermataTvScan() {\n" +
						"  var vids = document.querySelectorAll('video');\n" +
						"  for (var i = 0; i < vids.length; i++) {\n" +
						"    var v = vids[i];\n" +
						"    if (v.__ftv) continue;\n" +
						"    v.__ftv = 1;\n" +
						"    v.addEventListener('loadeddata', function() { fermataTvReport(this, 'loadeddata'); });\n" +
						"    v.addEventListener('playing', function() { fermataTvReport(this, 'playing'); });\n" +
						"    if (v.readyState > 0) fermataTvReport(v, 'ready');\n" +
						"  }\n" +
						"  setTimeout(fermataTvScan, 2000);\n" +
						"}\n" +
						"function fermataTvState() {\n" +
						"  var h = location.hash || '';\n" +
						"  var open = h.indexOf('watch') >= 0;\n" +
						"  if (!open) {\n" +
						"    var v = document.querySelector('video');\n" +
						"    if (v) {\n" +
						"      var r = v.getBoundingClientRect();\n" +
						"      open = r.width > innerWidth * 0.55 && r.height > innerHeight * 0.45 && v.readyState > 0;\n" +
						"    }\n" +
						"  }\n" +
						"  " + JS_EVENT + "(" + JS_TV_DIAG + ", 'state player=' + (open ? 1 : 0) + ' ' + location.href);\n" +
						"}\n" +
						"setInterval(fermataTvState, 1500);\n" +
						"setTimeout(function() {\n" +
						"  var vids = document.querySelectorAll('video');\n" +
						"  var live = false;\n" +
						"  for (var i = 0; i < vids.length; i++) if (vids[i].readyState > 0) live = true;\n" +
						"  if (!live) " + JS_EVENT + "(" + JS_TV_DIAG + ", 'video readyState=0 count=' + vids.length + ' ' + location.href);\n" +
						"}, 8000);\n" +
						"fermataTvScan();\n" +
						"}\n",
				null);
	}

	@Override
	public boolean onCarKey(KeyEvent event) {
		if (tvActive()) {
			boolean consumed = onTvKey(event);
			KnobLog.note(describeKey(event, consumed, "activity"));
			return consumed;
		}
		int code = event.getKeyCode();
		if (!isKnobKey(code)) return false;
		if (getRootView().findFocus() instanceof EditText) return false;
		if (event.getAction() != KeyEvent.ACTION_DOWN) return code != KeyEvent.KEYCODE_BACK || canGoBack();
		onKeyNavigation();
		installKnobJs();
		switch (code) {
			case KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_NAVIGATE_NEXT ->
					knob("fermataKnob.move(1)");
			case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS ->
					knob("fermataKnob.move(-1)");
			case KeyEvent.KEYCODE_DPAD_RIGHT -> knob("fermataKnob.horizontal(1)");
			case KeyEvent.KEYCODE_DPAD_LEFT -> knob("fermataKnob.horizontal(-1)");
			case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
					evaluateJavascript("fermataKnob.click()", value -> {
						if ((value != null) && value.contains("input")) showKeyboard(null);
					});
			case KeyEvent.KEYCODE_BACK -> {
				if (canGoBack()) goBack();
				else return false;
			}
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean onCarMotion(MotionEvent event) {
		if (tvActive()) return consumeTvMotion(event);
		if ((event.getAction() != MotionEvent.ACTION_SCROLL) ||
				!(event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER) ||
						(event.getAxisValue(MotionEvent.AXIS_SCROLL) != 0f))) return false;
		if (getRootView().findFocus() instanceof EditText) return false;
		float v = event.getAxisValue(MotionEvent.AXIS_SCROLL);
		if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
		if (v == 0f) return false;
		onKeyNavigation();
		installKnobJs();
		knob(v > 0f ? "fermataKnob.move(1)" : "fermataKnob.move(-1)");
		return true;
	}

	@Override
	public View focusSearch(int direction) {
		// Keep Android focus on the page. The car menu is the deliberate way out to the AA rail.
		if (tvActive()) return this;
		// Up, down and right stay on the page. Left can reach the nav bar.
		if (direction == View.FOCUS_LEFT) return super.focusSearch(direction);
		return this;
	}

	void knobSeek(int seconds) {
		evaluateJavascript(
				"(function(){var v=document.querySelector('video');var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');var t=v?v.currentTime:0;var n=Math.max(0,t+(" +
						seconds +
						"));if(p&&typeof p.seekTo==='function')p.seekTo(n,true);else if(v)v.currentTime=n;})()",
				null);
	}

	void knobToggle() {
		evaluateJavascript(
				"(function(){var v=document.querySelector('video');var p=document.querySelector('#movie_player')||document.querySelector('.html5-video-player');if(!v)return;if(v.paused){if(p&&p.playVideo)p.playVideo();else v.play();}else{if(p&&p.pauseVideo)p.pauseVideo();else v.pause();}})()",
				null);
	}

	void focusNavBar() {
		post(() -> {
			MainActivityDelegate a = MainActivityDelegate.get(getContext());
			if (a == null) return;
			NavBarView nb = a.getNavBar();
			if (nb != null) focusFirst(nb);
		});
	}

	private void knob(String call) {
		evaluateJavascript(call, null);
	}

	private void installKnobJs() {
		int edge = YoutubeJsInterface.JS_KNOB_EDGE;
		evaluateJavascript(KNOB_JS.replace("__EDGE__", Integer.toString(edge)), null);
	}

	private static boolean focusFirst(View v) {
		if ((v.getVisibility() == View.VISIBLE) && v.isShown() && v.isFocusable() && v.requestFocus())
			return true;
		if (v instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				if (focusFirst(group.getChildAt(i))) return true;
			}
		}
		return false;
	}

	private static boolean isKnobKey(int code) {
		return switch (code) {
			case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
					KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
					KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_NAVIGATE_NEXT,
					KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_BACK -> true;
			default -> false;
		};
	}

	private static final String KNOB_JS = """
			(function(){
			  if (window.fermataKnob) return;
			  var EDGE = __EDGE__;
			  if (!document.getElementById('fermata-knob-style')) {
			    var style = document.createElement('style');
			    style.id = 'fermata-knob-style';
			    style.textContent = '.fermata-knob{outline:3px solid #FFE600 !important;outline-offset:2px !important;border-radius:6px !important;}';
			    (document.head || document.documentElement).appendChild(style);
			  }
			  function visible(e) {
			    var r = e.getBoundingClientRect();
			    if (r.width < 12 || r.height < 12) return false;
			    if (r.bottom < 0 || r.top > window.innerHeight + 500) return false;
			    var s = getComputedStyle(e);
			    return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
			  }
			  window.fermataKnob = {
			    idx: -1,
			    collect: function() {
			      var nodes = document.querySelectorAll('a,button,input,textarea,select,[role="button"],[role="link"],[role="tab"],[role="menuitem"],[contenteditable="true"]');
			      var out = [];
			      for (var i = 0; i < nodes.length; i++) if (visible(nodes[i])) out.push(nodes[i]);
			      out.sort(function(a, b) {
			        var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
			        if (Math.abs(ra.top - rb.top) > 24) return ra.top - rb.top;
			        return ra.left - rb.left;
			      });
			      return out;
			    },
			    searchIndex: function(items) {
			      for (var i = 0; i < items.length; i++) {
			        var e = items[i];
			        var t = ((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('title')||'')+' '+(e.name||'')).toLowerCase();
			        if (t.indexOf('search') >= 0 || t.indexOf('поиск') >= 0) return i;
			      }
			      return -1;
			    },
			    apply: function(items) {
			      var prev = document.querySelectorAll('.fermata-knob');
			      for (var i = 0; i < prev.length; i++) prev[i].classList.remove('fermata-knob');
			      var el = items[this.idx];
			      if (!el) return;
			      el.classList.add('fermata-knob');
			      try { el.focus({preventScroll:true}); } catch (e) { try { el.focus(); } catch (e2) {} }
			      try { el.scrollIntoView({block:'center', inline:'nearest'}); } catch (e) { el.scrollIntoView(false); }
			    },
			    move: function(dir) {
			      var items = this.collect();
			      if (!items.length) return;
			      if (this.idx < 0 || !items[this.idx] || !items[this.idx].classList.contains('fermata-knob')) {
			        var s = this.searchIndex(items);
			        this.idx = (dir > 0 && s >= 0) ? s : (dir < 0 ? items.length - 1 : 0);
			      } else {
			        this.idx += (dir < 0 ? -1 : 1);
			        if (this.idx < 0) this.idx = 0;
			        if (this.idx >= items.length) this.idx = items.length - 1;
			      }
			      this.apply(items);
			    },
			    horizontal: function(dir) {
			      var items = this.collect();
			      if (!items.length) { if (dir < 0 && window.Fermata) Fermata.event(EDGE, 'left'); return; }
			      if (this.idx < 0) this.idx = 0;
			      var cur = items[this.idx] || items[0];
			      var r = cur.getBoundingClientRect();
			      var best = -1, bestD = 1e9;
			      for (var i = 0; i < items.length; i++) {
			        if (items[i] === cur) continue;
			        var q = items[i].getBoundingClientRect();
			        var dx = (q.left + q.width / 2) - (r.left + r.width / 2);
			        if (dir < 0 && dx > -8) continue;
			        if (dir > 0 && dx < 8) continue;
			        var dy = Math.abs((q.top + q.height / 2) - (r.top + r.height / 2));
			        if (dy > Math.max(80, r.height)) continue;
			        var d = Math.abs(dx) + dy;
			        if (d < bestD) { bestD = d; best = i; }
			      }
			      if (best < 0) {
			        if (dir < 0 && window.Fermata) Fermata.event(EDGE, 'left');
			        else this.move(dir);
			        return;
			      }
			      this.idx = best;
			      this.apply(items);
			    },
			    click: function() {
			      var items = this.collect();
			      var el = items[this.idx];
			      if (!el) return 'none';
			      var tag = el.tagName;
			      if (tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable) {
			        try { el.focus(); } catch (e) {}
			        try { el.click(); } catch (e) {}
			        return 'input';
			      }
			      try { el.click(); } catch (e) {}
			      return 'ok';
			    }
			  };
			})()
			""";

	protected void submitForm() {
		if (!me.aap.fermata.BuildConfig.AUTO) return;
		loadUrl("javascript:\n" +
				"var e = new KeyboardEvent('keydown',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);\n" +
				"e = new KeyboardEvent('keyup',\n" +
				"{ code: 'Enter', key: 'Enter', keyCode: 13, view: window, bubbles: true });\n" +
				"document.activeElement.dispatchEvent(e);");
	}

	private void attachListeners() {
		String debug = BuildConfig.D ? JS_EVENT + "(" + JS_VIDEO_FOUND + ", null);\n" : "";
		String scale = getAddon().getScale().prefName();
		loadUrl("javascript:\n" +
				// Muted autoplay previews in the feeds reuse the player, they are not user playback.
				// The video pages are the same as in isVideoUrl(): keep the two in sync.
				"function isPreview(v) {\n" +
				"  return v.muted && !/^\\/(watch|shorts|live|embed)/.test(location.pathname);\n" +
				"}\n" +
				"function videoEvent(v, e) {\n" +
				"  if (isPreview(v)) {\n" +
				"    if (!v.fermataPlaying) return;\n" +
				"    e = " + JS_VIDEO_PAUSED + ";\n" +
				"  }\n" +
				"  v.fermataPlaying = (e === " + JS_VIDEO_PLAYING + ");\n" +
				"  " + JS_EVENT + "(e, (e === " + JS_VIDEO_ENDED + ") ? fermataVideoUrl() : v.currentSrc);\n" +
				"}\n" +
				"function attachVideoListeners(v) {\n" +
				"  if (v.getAttribute('FermataAttached') === 'true') return;\n" +
				"  v.setAttribute('FermataAttached', 'true');\n" +
				"  v.style.objectFit = '" + scale + "';\n" + debug +
				"  if ((v.currentTime > 0) && !v.paused && !v.ended) videoEvent(v, " + JS_VIDEO_PLAYING + ");\n" +
				"  v.addEventListener('playing', function(e) {\n" +
				"    v.fermataStopped = false;\n" +
				"    videoEvent(v, " + JS_VIDEO_PLAYING + ");\n" +
				"  });\n" +
				// Stopping resets the position: not one to resume from. A muted video on a video page is still
				// playback (only a muted one elsewhere is a feed preview), so its progress is saved too.
				"  v.addEventListener('pause', function(e) {\n" +
				"    if (!isPreview(v) && !v.fermataStopped) " + JS_EVENT + "(" + JS_VIDEO_PROGRESS +
				", Math.floor(v.currentTime) + '|' + fermataVideoUrl());\n" +
				"    videoEvent(v, " + JS_VIDEO_PAUSED + ");\n" +
				"  });\n" +
				"  v.addEventListener('timeupdate', function(e) {\n" +
				"    if (isPreview(v) || v.fermataStopped) return;\n" +
				"    var t = Math.floor(v.currentTime);\n" +
				"    if ((t % 5 != 0) || (t === v.fermataProgress)) return;\n" +
				"    v.fermataProgress = t;\n" +
				"    " + JS_EVENT + "(" + JS_VIDEO_PROGRESS + ", t + '|' + fermataVideoUrl());\n" +
				"  });\n" +
				"  v.addEventListener('ended', function(e) { videoEvent(v, " + JS_VIDEO_ENDED + "); });\n" +
				// A muted preview unmuted in place becomes the user's playback
				"  v.addEventListener('volumechange', function(e) {\n" +
				"    if (!v.paused && !v.fermataPlaying && !isPreview(v)) videoEvent(v, " + JS_VIDEO_PLAYING + ");\n" +
				"  });\n" +
				"}\n" +
				// Search results and the home feed play videos inline, so the page URL is not
				// always the video URL. The player element knows the video id.
				"function fermataVideoUrl() {\n" +
				"  var h = location.href;\n" +
				"  if ((h.indexOf('/watch?') >= 0) || (h.indexOf('/shorts/') >= 0)) return h;\n" +
				"  var p = document.getElementById('movie_player') ||" +
				" document.querySelector('.html5-video-player');\n" +
				"  try {\n" +
				"    var d = p && p.getVideoData && p.getVideoData();\n" +
				"    if (d && d.video_id) return location.origin + '/watch?v=' + d.video_id;\n" +
				"  } catch (e) {}\n" +
				"  try {\n" +
				"    var u = p && p.getVideoUrl && p.getVideoUrl();\n" +
				"    if (u) return u;\n" +
				"  } catch (e) {}\n" +
				"  return '';\n" +
				"}\n" +
				"function findVideo() {\n" +
				"  var video = document.querySelectorAll('video');" +
				"  video.forEach(attachVideoListeners);\n" +
				"   setTimeout(findVideo, 1000);\n" +
				"}\n" +
				"if (!window.__fermataFullScreen) {\n" +
				"  window.__fermataFullScreen = true;\n" +
				"  document.addEventListener('fullscreenchange', function() {\n" +
				"    var e = document.fullscreenElement;\n" +
				"    " + JS_EVENT + "(" + JS_FULL_SCREEN + ", e ? e.tagName : null);\n" +
				"  });\n" +
				"}\n" +
				"findVideo();");
	}

	private void injectSponsorBlock() {
		String script = YoutubeSponsorBlock.getScript(getContext(), getAddon().getPreferenceStore());
		if (!script.isEmpty()) evaluateJavascript(script, result -> configureSponsorBlock());
		else configureSponsorBlock();
	}

	private void configureSponsorBlock() {
		evaluateJavascript("if (window.FermataSponsorBlock) window.FermataSponsorBlock.configure(" +
				YoutubeSponsorBlock.getConfigJson(getAddon().getPreferenceStore()) + ");", null);
	}

	protected boolean requestFullScreen() {
		// With YouTube's controls, its own button lets YouTube switch to its full screen layout. Without the
		// button (e.g. Shorts) the player goes full screen, and without the player the bare video, as without
		// the option. A refused request falls back to the next one. Only the click can't tell, so whether
		// full screen started is checked after a while.
		loadUrl("javascript:(function() {\n" +
				"  var v = document.querySelector('video');\n" +
				"  if (!v) return;\n" +
				"  var p = " + getAddon().youtubePlayerControls() + " && v.closest('#player-container-id');\n" +
				"  var b = p && p.querySelector('button.fullscreen-icon');\n" +
				"  function fs(e) {\n" +
				"    if ('requestFullscreen' in e) return e.requestFullscreen();\n" +
				"    if ('webkitRequestFullscreen' in e) return Promise.resolve(e.webkitRequestFullscreen());\n" +
				"    return Promise.reject('Method requestFullscreen not found in ' + e);\n" +
				"  }\n" +
				"  function video() {\n" +
				"    fs(v).catch(function(err) { " + JS_EVENT + "(" + JS_ERR + ", '' + err); });\n" +
				"  }\n" +
				"  function player() { fs(p).catch(video); }\n" +
				"  if (!p) video();\n" +
				"  else if (!b) player();\n" +
				"  else {\n" +
				"    b.click();\n" +
				"    setTimeout(function() { if (!document.fullscreenElement) player(); }, 1500);\n" +
				"  }\n" +
				"})();");
		return true;
	}

	void play() {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.play();");
	}

	void pause() {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.pause();");
	}

	void stop() {
		// Not a position to save for "Resume last video": the listeners skip the progress of this reset
		loadUrl("javascript:var v = document.querySelector('video');\n" +
				"if (v != null) { v.fermataStopped = true; v.currentTime = 0; v.pause(); }");
	}

	void prev() {
		prevNext(false);
	}

	void next() {
		prevNext(true);
	}

	private void prevNext(boolean next) {
		evaluateJavascript("""
				(function() {
				  const p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				  const fn = '%s';
				  if (!p || (typeof p[fn] !== 'function')) return false;
				  p[fn]();
				  return true;
				})();
				""".formatted(next ? "nextVideo" : "previousVideo"), r -> {
			if ("true".equals(r)) return;
			// Player API not available, fall back to the page's prev/next buttons
			FermataChromeClient chrome = getWebChromeClient();
			if (chrome == null) return;
			chrome.exitFullScreen().thenRun(() -> evaluateJavascript("""
					setTimeout(() => {
					  const buttons = document.querySelectorAll('button.player-middle-controls-prev-next-button');
					  if (buttons.length > %1$d) buttons[%1$d].click();
					}, 600);
					""".formatted(next ? 1 : 0), null));
		});
	}

	FutureSupplier<Long> getDuration() {
		return getMilliseconds("duration");
	}

	FutureSupplier<Long> getPosition() {
		return getMilliseconds("currentTime");
	}

	FutureSupplier<String> getVideoQualities() {
		Promise<String> p = js.getResultPromise();
		loadUrl("javascript:\n" +
				"function retryGetVideoQualities(attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(getVideoQualities, 100, attempt + 1, openMenu);\n" +
				"  else " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", null);\n" +
				"  return null;\n" +
				"}\n" +
				"function getVideoQualities(attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retryGetVideoQualities(attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retryGetVideoQualities(attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var result = '';\n" +
				"  for (let i = 0; i < options.length; i++) {\n" +
				"    if (i != 0) result += ';';\n" +
				"    if (i == select.selectedIndex) result += '*';\n" +
				"    result += options[i].innerText;\n" +
				"  }\n" +
				"  " + JS_EVENT + '(' + JS_VIDEO_QUALITIES + ", result);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return result;\n" +
				"}\n" +
				"getVideoQualities(0, true);");
		return p;
	}

	void setVideoQuality(int idx) {
		loadUrl("javascript:\n" +
				"function retrySetVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (attempt < 10) setTimeout(setVideoQuality, 100, idx, attempt + 1, openMenu);\n" +
				"  return false;\n" +
				"}\n" +
				"function setVideoQuality(idx, attempt, openMenu) {\n" +
				"  if (openMenu) {\n" +
				"    var b = document.querySelector('.player-settings-icon');\n" +
				"    if (b == null) return retrySetVideoQuality(idx, attempt, true);\n" +
				"    b.click();\n" +
				"  }\n" +
				"  var settings = document.querySelector('.player-quality-settings');\n" +
				"  if (settings == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var select = settings.querySelector('.select');\n" +
				"  if (select == null) return retrySetVideoQuality(idx, attempt, false);\n" +
				"  var options = select.querySelectorAll('.option');\n" +
				"  var evt = document.createEvent(\"HTMLEvents\");\n" +
				"  evt.initEvent(\"change\", true, true);\n" +
				"  select.selectedIndex = idx;\n" +
				"  options[idx].selected = true;\n" +
				"  select.dispatchEvent(evt);\n" +
				"  setTimeout(()=> {settings.parentNode.parentNode.querySelector('" +
				".c3-material-button-button').click();}, 100);\n" +
				"  return true;\n" +
				"}\n" +
				"setVideoQuality(" + idx + ", 0, true);");
	}

	void setHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"  var state = window.__fermataQ = { player: null, handler: null, timeout: null, attempts: 0 };\n" +
				"  function getPlayer() {\n" +
				"    return document.querySelector('#movie_player') || document.querySelector('.html5-video-player');\n" +
				"  }\n" +
				"  function applyHighest(p) {\n" +
				"    if (!p || typeof p.getAvailableQualityLevels !== 'function') return false;\n" +
				"    var levels = p.getAvailableQualityLevels();\n" +
				"    if (!levels || levels.length === 0) return false;\n" +
				"    var best = null;\n" +
				"    for (var i = 0; i < levels.length; i++) {\n" +
				"      if (levels[i] !== 'auto') { best = levels[i]; break; }\n" +
				"    }\n" +
				"    if (!best) return false;\n" +
				"    if (p.getPlaybackQuality && p.getPlaybackQuality() === best) return true;\n" +
				"    if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange(best, best);\n" +
				"    else if (typeof p.setPlaybackQuality === 'function') p.setPlaybackQuality(best);\n" +
				"    else return false;\n" +
				"    return true;\n" +
				"  }\n" +
				"  function install() {\n" +
				"    var p = getPlayer();\n" +
				"    if (!p || typeof p.addEventListener !== 'function') {\n" +
				"      if (++state.attempts < 50) state.timeout = setTimeout(install, 200);\n" +
				"      return;\n" +
				"    }\n" +
				"    state.player = p;\n" +
				"    state.handler = function(s) {\n" +
				"      if ((s === 1) || (s === 3)) applyHighest(getPlayer() || p);\n" +
				"    };\n" +
				"    p.addEventListener('onStateChange', state.handler);\n" +
				"    applyHighest(p);\n" +
				"  }\n" +
				"  install();\n" +
				"})();");
	}

	void clearHighestVideoQuality() {
		loadUrl("javascript:\n" +
				"(function() {\n" +
				CLEAR_HIGHEST_VIDEO_QUALITY_JS +
				"  clearFermataQ();\n" +
				"})();");
	}

	private FutureSupplier<Long> getMilliseconds(String value) {
		Promise<Long> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v." + value +
						" : 0})();",
				v -> {
					try {
						p.complete((long) (Double.parseDouble(v) * 1000));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(0L);
					}
				});
		return p;
	}

	void setPosition(long position) {
		double pos = position / 1000d;
		// Seek through YouTube's player API when available: setting currentTime on the media
		// element directly may be ignored or reverted by the player.
		evaluateJavascript("""
				(function() {
				  const s = %s;
				  const p = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
				  if (p && (typeof p.seekTo === 'function')) { p.seekTo(s, true); return; }
				  const v = document.querySelector('video');
				  if (v != null) v.currentTime = s;
				})();
				""".formatted(Double.toString(pos)), null);
	}

	FutureSupplier<Float> getSpeed() {
		Promise<Float> p = new Promise<>();
		evaluateJavascript(
				"(function(){var v = document.querySelector('video'); return (v != null) ? v" +
						".playbackRate" +
						" " +
						": 0})();",
				v -> {
					try {
						p.complete(Float.parseFloat(v));
					} catch (NumberFormatException ex) {
						Log.d(ex);
						p.complete(1f);
					}
				});
		return p;
	}

	void setSpeed(float speed) {
		loadUrl("javascript:var v = document.querySelector('video'); if (v != null) v.playbackRate =" +
				" " +
				speed + ";");
	}

	FutureSupplier<String> getVideoTitle() {
		Promise<String> p = new Promise<>();
		// The page title is updated only some time after a video is opened, the player knows it already
		evaluateJavascript("""
				(function() {
				  var v = document.querySelector('video');
				  var p = v && v.closest('#movie_player');
				  var d = p && p.getVideoData && p.getVideoData();
				  return (d && d.title) || document.title;
				})()""", json -> {
			// evaluateJavascript() returns its result as JSON, so a string comes quoted and escaped
			try {
				Object v = new JSONTokener(json).nextValue();
				p.complete((v instanceof String) ? (String) v : null);
			} catch (JSONException ex) {
				p.complete(json);
			}
		});
		return p;
	}

	void setScale(YoutubeAddon.VideoScale scale) {
		getAddon().setScale(scale);
		String p = scale.prefName();
		loadUrl("javascript:" +
				"document.querySelectorAll('video')" +
				".forEach(v=> v.style.objectFit = '" + p + "');");
	}
}
