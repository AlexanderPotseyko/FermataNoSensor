package me.aap.fermata.addon.web.yt;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.web.FermataChromeClient;
import me.aap.fermata.addon.web.R;
import me.aap.fermata.ui.activity.CarInputSink;
import me.aap.fermata.ui.view.VideoInfoView;
import me.aap.fermata.ui.view.VideoView;

/**
 * Full-screen YouTube surface. The knob seeks and pauses here instead of moving page focus.
 */
public class YoutubeVideoView extends VideoView implements CarInputSink {

	public YoutubeVideoView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void init(Context context) {
		addView(new FrameLayout(context), new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		addInfoView(context);
	}

	@NonNull
	@Override
	public VideoInfoView getVideoInfoView() {
		return (VideoInfoView) getChildAt(1);
	}

	@Nullable
	@Override
	public SurfaceView getSubtitleSurface() {
		return null;
	}

	@Override
	public boolean onCarKey(KeyEvent event) {
		int code = event.getKeyCode();
		if (!isPlayerKey(code)) return false;
		YoutubeWebView web = web();
		if (web == null) return false;
		if (code == KeyEvent.KEYCODE_BACK) {
			if (event.getAction() == KeyEvent.ACTION_DOWN) leave(web);
			return true;
		}
		if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
		switch (code) {
			case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
					web.knobToggle();
			case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> web.knobSeek(10);
			case KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> web.knobSeek(-10);
			default -> {
			}
		}
		return true;
	}

	@Override
	public boolean onCarMotion(MotionEvent event) {
		if (event.getAction() != MotionEvent.ACTION_SCROLL) return false;
		float v = event.getAxisValue(MotionEvent.AXIS_SCROLL);
		if (v == 0f) v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
		if (v == 0f) return false;
		YoutubeWebView web = web();
		if (web == null) return false;
		web.knobSeek(v > 0f ? 10 : -10);
		return true;
	}

	private void leave(YoutubeWebView web) {
		FermataChromeClient chrome = web.getWebChromeClient();
		if ((chrome != null) && chrome.isFullScreen()) chrome.onHideCustomView();
		else if (web.canGoBack()) web.goBack();
	}

	@Nullable
	private YoutubeWebView web() {
		View parent = (View) getParent();
		if (parent == null) return null;
		View v = parent.findViewById(R.id.ytWebView);
		return (v instanceof YoutubeWebView w) ? w : null;
	}

	private static boolean isPlayerKey(int code) {
		return switch (code) {
			case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
					KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
					KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> true;
			default -> false;
		};
	}
}
