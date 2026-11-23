package me.aap.fermata.addon.web.yt;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import me.aap.fermata.ui.activity.KnobLog;

/**
 * Knob menu and the loading/error panel drawn over the TV page.
 */
final class YoutubeTvOverlay extends LinearLayout implements YoutubeWebView.TvHost {
	private static final int MAX_TRIES = 3;
	private final YoutubeWebView web;
	private final ProgressBar progress;
	private final TextView message;
	private final LinearLayout items;
	private int tries;
	private Runnable retry;
	private boolean open;

	YoutubeTvOverlay(ViewGroup parent, YoutubeWebView web) {
		super(parent.getContext());
		this.web = web;
		setOrientation(VERTICAL);
		setGravity(Gravity.CENTER_HORIZONTAL);
		setBackgroundColor(0xF0101010);
		setVisibility(GONE);
		int pad = dp(24);
		setPadding(pad, pad, pad, pad);
		setFocusable(false);
		progress = new ProgressBar(getContext());
		addView(progress, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
		message = label(18, false);
		message.setGravity(Gravity.CENTER);
		addView(message, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		items = new LinearLayout(getContext());
		items.setOrientation(VERTICAL);
		items.setGravity(Gravity.CENTER_HORIZONTAL);
		addView(items, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		parent.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
	}

	@Override
	public boolean isOpen() {
		return open && (getVisibility() == VISIBLE) && (items.getChildCount() > 0);
	}

	@Override
	public void showMenu() {
		cancelRetry();
		fill(false, me.aap.fermata.R.string.yt_menu_refresh, me.aap.fermata.R.string.yt_menu_home,
				me.aap.fermata.R.string.yt_menu_close);
		message.setText("");
		progress.setVisibility(GONE);
		reveal();
		KnobLog.note("menu open");
	}

	@Override
	public void closeMenu() {
		if (getVisibility() != VISIBLE) return;
		open = false;
		setVisibility(GONE);
		items.removeAllViews();
		web.setVisibility(VISIBLE);
		web.requestFocus();
		KnobLog.note("menu close");
	}

	@Override
	public void onLoadStart() {
		cancelRetry();
		items.removeAllViews();
		open = false;
		progress.setVisibility(VISIBLE);
		message.setText(me.aap.fermata.R.string.yt_loading);
		setVisibility(VISIBLE);
		bringToFront();
		// The WebView surface paints above siblings. Hide it so the spinner stays visible.
		web.setVisibility(INVISIBLE);
	}

	@Override
	public void onLoaded() {
		tries = 0;
		cancelRetry();
		if (isOpen()) return;
		setVisibility(GONE);
		web.setVisibility(VISIBLE);
	}

	@Override
	public void onLoadError(String description) {
		KnobLog.note("tv error overlay: " + description);
		web.setVisibility(INVISIBLE);
		if (tries < MAX_TRIES) {
			tries++;
			progress.setVisibility(VISIBLE);
			message.setText(getContext().getString(me.aap.fermata.R.string.yt_retrying, tries));
			items.removeAllViews();
			open = false;
			setVisibility(VISIBLE);
			long delay = 1000L << (tries - 1);
			retry = () -> {
				retry = null;
				web.loadUrl(YoutubeWebView.homeUrl());
			};
			postDelayed(retry, delay);
			return;
		}
		cancelRetry();
		progress.setVisibility(GONE);
		message.setText(me.aap.fermata.R.string.yt_load_error);
		fill(true, me.aap.fermata.R.string.yt_menu_retry, me.aap.fermata.R.string.yt_menu_open);
		reveal();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if ((event.getKeyCode() == KeyEvent.KEYCODE_BACK) &&
				(event.getAction() == KeyEvent.ACTION_DOWN)) {
			KnobLog.note(YoutubeWebView.describeKey(event, true, "menu"));
			closeMenu();
			return true;
		}
		boolean consumed = super.dispatchKeyEvent(event);
		KnobLog.note(YoutubeWebView.describeKey(event, consumed, "menu"));
		return consumed;
	}

	private void reveal() {
		open = true;
		setVisibility(VISIBLE);
		if (items.getChildCount() > 0) items.getChildAt(0).requestFocus();
	}

	private void fill(boolean error, int... titles) {
		items.removeAllViews();
		for (int i = 0; i < titles.length; i++) {
			Item item = new Item(getContext(), i == titles.length - 1);
			item.setText(titles[i]);
			int title = titles[i];
			item.setOnClickListener(v -> select(title));
			LinearLayout.LayoutParams lp = new LayoutParams(dp(420), LayoutParams.WRAP_CONTENT);
			lp.topMargin = dp(8);
			items.addView(item, lp);
		}
	}

	private void select(int title) {
		long now = SystemClock.uptimeMillis();
		KnobLog.note("menu select " + getContext().getString(title));
		if (title == me.aap.fermata.R.string.yt_menu_close) {
			closeMenu();
		} else if (title == me.aap.fermata.R.string.yt_menu_open) {
			showMenu();
		} else if (title == me.aap.fermata.R.string.yt_menu_home) {
			closeMenu();
			web.loadUrl(YoutubeWebView.TV_HOME);
		} else if ((title == me.aap.fermata.R.string.yt_menu_refresh) ||
				(title == me.aap.fermata.R.string.yt_menu_retry)) {
			tries = 0;
			closeMenu();
			web.reloadTv();
		}
		KnobLog.note("menu select done " + (SystemClock.uptimeMillis() - now) + "ms");
	}

	private void cancelRetry() {
		if (retry != null) {
			removeCallbacks(retry);
			retry = null;
		}
	}

	private TextView label(int sp, boolean focusable) {
		TextView view = new TextView(getContext());
		view.setTextColor(Color.WHITE);
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		view.setFocusable(focusable);
		return view;
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private final class Item extends TextView {
		private final boolean releaseDown;

		Item(Context context, boolean releaseDown) {
			super(context);
			this.releaseDown = releaseDown;
			setTextColor(Color.WHITE);
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
			setTypeface(Typeface.DEFAULT_BOLD);
			setGravity(Gravity.CENTER);
			setFocusable(true);
			setBackgroundColor(0x33FFFFFF);
			int pad = dp(12);
			setPadding(pad, pad, pad, pad);
			setOnFocusChangeListener((v, has) -> {
				v.setBackgroundColor(has ? 0xFFFFE600 : 0x33FFFFFF);
				setTextColor(has ? Color.BLACK : Color.WHITE);
			});
			if (releaseDown) setTextColor(Color.WHITE);
		}

		@Override
		public View focusSearch(int direction) {
			if (releaseDown && (direction == FOCUS_DOWN)) {
				KnobLog.note("focus released toward AA rail");
				web.allowFocusLeave();
				return null;
			}
			return super.focusSearch(direction);
		}
	}
}
