package me.aap.fermata.ui.activity;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Lets the car window and the media session reach the YouTube TV page without the base module
 * depending on the web feature.
 */
public final class TvTransport {
	public interface Target {
		boolean play();

		boolean pause();

		boolean toggle();

		boolean seek(int seconds);

		boolean onKey(KeyEvent event, String path);

		boolean onMotion(MotionEvent event, String path);

		boolean onBack(String path);
	}

	public interface Playback {
		void onTvPlayback(boolean playerOpen, boolean paused);
	}

	private static Target target;
	private static Playback playback;

	private TvTransport() {
	}

	public static void set(Target next) {
		target = next;
	}

	public static void setPlayback(Playback next) {
		playback = next;
	}

	public static boolean play() {
		Target t = target;
		return (t != null) && t.play();
	}

	public static boolean pause() {
		Target t = target;
		return (t != null) && t.pause();
	}

	public static boolean toggle() {
		Target t = target;
		return (t != null) && t.toggle();
	}

	public static boolean seek(int seconds) {
		Target t = target;
		return (t != null) && t.seek(seconds);
	}

	public static boolean onKey(KeyEvent event, String path) {
		Target t = target;
		return (t != null) && t.onKey(event, path);
	}

	public static boolean onMotion(MotionEvent event, String path) {
		Target t = target;
		return (t != null) && t.onMotion(event, path);
	}

	public static boolean onBack(String path) {
		Target t = target;
		return (t != null) && t.onBack(path);
	}

	public static void playback(boolean playerOpen, boolean paused) {
		Playback p = playback;
		if (p != null) p.onTvPlayback(playerOpen, paused);
	}
}
