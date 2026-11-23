package me.aap.fermata.ui.activity;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

/**
 * A view that handles the car rotary knob itself, instead of the pointer cursor.
 */
public interface CarInputSink {
	boolean onCarKey(KeyEvent event);

	boolean onCarMotion(MotionEvent event);

	@Nullable
	static View findView(@Nullable View root) {
		if ((root == null) || (root.getVisibility() != View.VISIBLE)) return null;
		if (root instanceof ViewGroup group) {
			for (int i = group.getChildCount() - 1; i >= 0; i--) {
				View found = findView(group.getChildAt(i));
				if (found != null) return found;
			}
		}
		return (root instanceof CarInputSink) ? root : null;
	}
}
