package me.aap.fermata.ui.activity;

/**
 * Maps a head-unit rotary event onto a direction. Plain Java so it can be unit tested.
 * <p>
 * DHU {@code dpad rotate right} (keyboard {@code 2}) is clockwise and must move down by default.
 * Android Automotive reports that as {@code KEYCODE_NAVIGATE_NEXT}. A mouse-wheel style
 * {@code AXIS_SCROLL} is positive for the same clockwise step. A {@code DPAD_UP} that arrives
 * with {@code SOURCE_ROTARY_ENCODER} is the raw event YouTube TV was treating as focus-up.
 * Plain D-pad nudges ({@code dpad up} and the arrow keys) are not rotation.
 */
public final class KnobMap {
	public static final int DPAD_UP = 19;
	public static final int DPAD_DOWN = 20;
	public static final int DPAD_LEFT = 21;
	public static final int DPAD_RIGHT = 22;
	public static final int NAVIGATE_PREVIOUS = 260;
	public static final int NAVIGATE_NEXT = 261;
	/** {@link android.view.InputDevice#SOURCE_ROTARY_ENCODER} */
	public static final int SOURCE_ROTARY_ENCODER = 0x00400000;

	public enum Move {NONE, UP, DOWN, LEFT, RIGHT}

	private KnobMap() {
	}

	public static Move rotation(int keyCode, int source, float scroll, boolean invert,
															boolean horizontal) {
		int dir = clockwise(keyCode, source, scroll);
		if (dir == 0) return Move.NONE;
		if (invert) dir = -dir;
		if (horizontal) return dir > 0 ? Move.RIGHT : Move.LEFT;
		return dir > 0 ? Move.DOWN : Move.UP;
	}

	/** +1 clockwise, -1 counter-clockwise, 0 if this event is not a rotation. */
	public static int clockwise(int keyCode, int source, float scroll) {
		if (scroll > 0f) return 1;
		if (scroll < 0f) return -1;
		if (keyCode == NAVIGATE_NEXT) return 1;
		if (keyCode == NAVIGATE_PREVIOUS) return -1;
		if ((source & SOURCE_ROTARY_ENCODER) != 0) {
			if ((keyCode == DPAD_UP) || (keyCode == DPAD_RIGHT)) return 1;
			if ((keyCode == DPAD_DOWN) || (keyCode == DPAD_LEFT)) return -1;
		}
		return 0;
	}

	public static int keyCode(Move move) {
		return switch (move) {
			case UP -> DPAD_UP;
			case DOWN -> DPAD_DOWN;
			case LEFT -> DPAD_LEFT;
			case RIGHT -> DPAD_RIGHT;
			default -> 0;
		};
	}
}
