package me.aap.fermata.ui.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.utils.log.Log;
import me.aap.utils.ui.UiUtils;

/**
 * Rolling log of knob, key and motion events. Written only while the setting is on.
 */
public final class KnobLog {
	private static final long MAX_BYTES = 1024 * 1024;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "knob-log");
		t.setDaemon(true);
		return t;
	});
	private static final SimpleDateFormat TIME =
			new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
	private static long lastHash;
	private static volatile String mode = "web";

	private KnobLog() {
	}

	public static boolean enabled() {
		return FermataApplication.get().getPreferenceStore()
				.getBooleanPref(MainActivityPrefs.KNOB_LOG);
	}

	public static File file() {
		Context ctx = FermataApplication.get();
		File dir = ctx.getExternalFilesDir(null);
		if ((dir == null) || (!dir.isDirectory() && !dir.mkdirs())) dir = ctx.getFilesDir();
		return new File(dir, "knob.log");
	}

	/** Marks the YouTube mode for the following lines. Writes a session header when logging is on. */
	public static void session(String next) {
		mode = (next == null) || next.isEmpty() ? "web" : next;
		if (!enabled()) return;
		String header = stamp() + " mode=" + mode + " --- session\n";
		IO.execute(() -> append(header));
	}

	public static void note(String text) {
		if (!enabled() || (text == null)) return;
		String line = stamp() + " mode=" + mode + " note " + text + "\n";
		Log.i("knob " + text);
		IO.execute(() -> append(line));
	}

	public static void logKey(View focus, KeyEvent event) {
		if (!enabled() || (event == null)) return;
		long hash = event.getEventTime() ^ event.getAction() ^ event.getKeyCode() ^
				event.getRepeatCount() ^ event.getSource();
		if (hash == lastHash) return;
		lastHash = hash;
		String focused = focus(focus);
		IO.execute(() -> append(line(event, focused)));
	}

	public static void logMotion(View focus, MotionEvent event) {
		if (!enabled() || (event == null)) return;
		// Pointer drags flood the file. Keys, presses and scrolls are the knob.
		if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return;
		long hash = event.getEventTime() ^ event.getAction() ^ event.getSource();
		if (hash == lastHash) return;
		lastHash = hash;
		String focused = focus(focus);
		IO.execute(() -> append(line(event, focused)));
	}

	public static void share(Context ctx) {
		File f = file();
		if (!f.isFile() || (f.length() == 0)) {
			UiUtils.showInfo(ctx, ctx.getString(R.string.knob_log_empty));
			return;
		}
		try {
			Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".FileProvider", f);
			Intent i = new Intent(Intent.ACTION_SEND);
			i.setType("text/plain");
			i.putExtra(Intent.EXTRA_STREAM, uri);
			i.setClipData(ClipData.newRawUri("knob.log", uri));
			i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			Intent chooser = Intent.createChooser(i, ctx.getString(R.string.knob_log_share));
			chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			if (!(ctx instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(chooser);
		} catch (Exception err) {
			Log.e(err, "Failed to share knob log");
			UiUtils.showInfo(ctx, String.valueOf(err.getMessage()));
		}
	}

	public static void clear(Context ctx) {
		IO.execute(() -> {
			File f = file();
			File prev = new File(f.getParentFile(), "knob.log.1");
			//noinspection ResultOfMethodCallIgnored
			f.delete();
			//noinspection ResultOfMethodCallIgnored
			prev.delete();
		});
		UiUtils.showInfo(ctx, ctx.getString(R.string.knob_log_cleared));
	}

	private static String focus(View v) {
		if (v == null) return "none";
		String id = "";
		try {
			if (v.getId() != View.NO_ID) id = "#" + v.getResources().getResourceEntryName(v.getId());
		} catch (Exception ignore) {
		}
		return v.getClass().getSimpleName() + id;
	}

	private static String line(KeyEvent event, String focus) {
		return stamp() + " mode=" + mode + " key action=" + action(event.getAction()) +
				" code=" + KeyEvent.keyCodeToString(event.getKeyCode()) +
				"(" + event.getKeyCode() + ") repeat=" + event.getRepeatCount() +
				" source=0x" + Integer.toHexString(event.getSource()) +
				" device=" + device(event.getDeviceId()) +
				" focus=" + focus + "\n";
	}

	private static String line(MotionEvent event, String focus) {
		return stamp() + " mode=" + mode + " motion action=" + action(event.getActionMasked()) +
				" source=0x" + Integer.toHexString(event.getSource()) +
				" device=" + device(event.getDeviceId()) +
				" scroll=" + event.getAxisValue(MotionEvent.AXIS_SCROLL) +
				" vscroll=" + event.getAxisValue(MotionEvent.AXIS_VSCROLL) +
				" hscroll=" + event.getAxisValue(MotionEvent.AXIS_HSCROLL) +
				" x=" + event.getX() + " y=" + event.getY() +
				" focus=" + focus + "\n";
	}

	private static String stamp() {
		synchronized (TIME) {
			return TIME.format(new Date());
		}
	}

	private static String action(int action) {
		return switch (action) {
			case KeyEvent.ACTION_DOWN -> "DOWN";
			case KeyEvent.ACTION_UP -> "UP";
			case MotionEvent.ACTION_SCROLL -> "SCROLL";
			case MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS";
			default -> String.valueOf(action);
		};
	}

	private static String device(int id) {
		InputDevice device = InputDevice.getDevice(id);
		String name = (device == null) ? "?" : device.getName();
		return id + ":" + name;
	}

	private static void append(String line) {
		try {
			File f = file();
			if (f.length() > MAX_BYTES) {
				File prev = new File(f.getParentFile(), "knob.log.1");
				//noinspection ResultOfMethodCallIgnored
				prev.delete();
				//noinspection ResultOfMethodCallIgnored
				f.renameTo(prev);
			}
			try (FileOutputStream out = new FileOutputStream(f, true)) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception err) {
			Log.e(err, "Knob log write failed");
		}
	}
}
