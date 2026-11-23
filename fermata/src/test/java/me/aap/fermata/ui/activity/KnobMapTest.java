package me.aap.fermata.ui.activity;

import static me.aap.fermata.ui.activity.KnobMap.DPAD_DOWN;
import static me.aap.fermata.ui.activity.KnobMap.DPAD_LEFT;
import static me.aap.fermata.ui.activity.KnobMap.DPAD_RIGHT;
import static me.aap.fermata.ui.activity.KnobMap.DPAD_UP;
import static me.aap.fermata.ui.activity.KnobMap.NAVIGATE_NEXT;
import static me.aap.fermata.ui.activity.KnobMap.NAVIGATE_PREVIOUS;
import static me.aap.fermata.ui.activity.KnobMap.SOURCE_ROTARY_ENCODER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class KnobMapTest {

	@Test
	public void clockwiseIsDownByDefault() {
		assertEquals(KnobMap.Move.DOWN, KnobMap.rotation(NAVIGATE_NEXT, 0, 0f, false, false));
		assertEquals(KnobMap.Move.UP, KnobMap.rotation(NAVIGATE_PREVIOUS, 0, 0f, false, false));
		assertEquals(KnobMap.Move.DOWN, KnobMap.rotation(0, 0, 1f, false, false));
		assertEquals(KnobMap.Move.UP, KnobMap.rotation(0, 0, -1f, false, false));
		assertEquals(KnobMap.Move.DOWN,
				KnobMap.rotation(DPAD_UP, SOURCE_ROTARY_ENCODER, 0f, false, false));
		assertEquals(DPAD_DOWN, KnobMap.keyCode(KnobMap.Move.DOWN));
	}

	@Test
	public void invertAndAxis() {
		assertEquals(KnobMap.Move.UP, KnobMap.rotation(NAVIGATE_NEXT, 0, 0f, true, false));
		assertEquals(KnobMap.Move.RIGHT, KnobMap.rotation(NAVIGATE_NEXT, 0, 0f, false, true));
		assertEquals(KnobMap.Move.LEFT, KnobMap.rotation(0, 0, 1f, true, true));
	}

	@Test
	public void nudgesAreNotRotation() {
		assertEquals(KnobMap.Move.NONE, KnobMap.rotation(DPAD_UP, 0, 0f, false, false));
		assertEquals(KnobMap.Move.NONE, KnobMap.rotation(DPAD_DOWN, 0, 0f, false, false));
		assertEquals(KnobMap.Move.NONE, KnobMap.rotation(DPAD_LEFT, 0, 0f, false, false));
		assertEquals(KnobMap.Move.NONE, KnobMap.rotation(DPAD_RIGHT, 0, 0f, false, false));
	}

	@Test
	public void shareLinkKeepsTime() {
		YoutubeShare.Link link = YoutubeShare.parse(
				"watch this https://youtu.be/dQw4w9WgXcQ?t=1m30s thanks");
		assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", link.watchUrl());
		assertEquals(90, link.sec());
		link = YoutubeShare.parse("https://www.youtube.com/watch?v=abcdefghijk&t=75");
		assertEquals(75, link.sec());
		link = YoutubeShare.parse("https://m.youtube.com/shorts/abcdefghijk");
		assertEquals("abcdefghijk", YoutubeShare.videoId(link.watchUrl()));
		assertNull(YoutubeShare.parse("not a link"));
	}
}
