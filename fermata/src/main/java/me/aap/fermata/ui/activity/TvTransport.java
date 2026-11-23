package me.aap.fermata.ui.activity;

/**
 * Lets the media session reach the YouTube TV page without the base module
 * depending on the web feature.
 */
public final class TvTransport {
	public interface Target {
		boolean play();

		boolean pause();

		boolean toggle();

		boolean seek(int seconds);
	}

	private static Target target;

	private TvTransport() {
	}

	public static void set(Target next) {
		target = next;
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
}
