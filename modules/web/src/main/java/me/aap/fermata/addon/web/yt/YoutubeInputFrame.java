package me.aap.fermata.addon.web.yt;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.web.R;
import me.aap.fermata.ui.activity.KnobLog;

/**
 * Sees keys that the car WebView would otherwise swallow before the activity.
 */
public class YoutubeInputFrame extends FrameLayout {
	private YoutubeWebView web;

	public YoutubeInputFrame(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		web = findViewById(R.id.ytWebView);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		boolean consumed = super.dispatchKeyEvent(event);
		if (consumed || (web == null) || !web.tvActive() || web.menuOpen()) return consumed;
		if (YoutubeWebView.holdsFocus(event)) {
			KnobLog.note(YoutubeWebView.describeKey(event, true, "frame"));
			return true;
		}
		return false;
	}

	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent event) {
		if ((web != null) && web.tvActive() && web.consumeTvMotion(event)) return true;
		return super.dispatchGenericMotionEvent(event);
	}
}
