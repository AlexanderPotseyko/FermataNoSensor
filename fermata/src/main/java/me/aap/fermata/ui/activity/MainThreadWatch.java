package me.aap.fermata.ui.activity;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Printer;

import me.aap.utils.log.Log;

/**
 * Logs when a main-thread message runs longer than 200 ms. CarService treats a stuck client as an
 * input ANR, so the line is the trace for a grey Android Auto screen.
 */
public final class MainThreadWatch {
	private static boolean installed;

	private MainThreadWatch() {
	}

	public static void install() {
		if (installed) return;
		Looper looper = Looper.getMainLooper();
		if (looper == null) return;
		installed = true;
		looper.setMessageLogging(new Printer() {
			private long start;

			@Override
			public void println(String message) {
				if (message == null) return;
				if (message.startsWith(">>>>>")) {
					start = SystemClock.uptimeMillis();
					return;
				}
				if (start == 0) return;
				long dt = SystemClock.uptimeMillis() - start;
				start = 0;
				if (dt > 200) Log.w("main thread ", dt, " ms");
			}
		});
	}
}
