package me.aap.fermata.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import me.aap.fermata.R;
import me.aap.utils.ui.UiUtils;

/**
 * A YouTube link shared from the phone app, opened later on the car screen at the same position.
 */
public final class YoutubeShare {
	private static final String PREF = "yt-share";
	private static final String KEY_URL = "url";
	private static final String KEY_SEC = "sec";

	public record Link(String watchUrl, int sec) {
	}

	private YoutubeShare() {
	}

	public static void remember(Context ctx, @Nullable Intent intent) {
		if ((ctx == null) || (intent == null)) return;
		String text = intent.getStringExtra(Intent.EXTRA_TEXT);
		if ((text == null) || text.isEmpty()) text = intent.getDataString();
		Link link = parse(text);
		if (link == null) return;
		synchronized (YoutubeShare.class) {
			ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
					.putString(KEY_URL, link.watchUrl).putInt(KEY_SEC, link.sec).commit();
		}
		UiUtils.showInfo(ctx, ctx.getString(R.string.yt_share_saved));
	}

	@Nullable
	public static Link take(Context ctx) {
		if (ctx == null) return null;
		synchronized (YoutubeShare.class) {
			SharedPreferences ps = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
			String url = ps.getString(KEY_URL, null);
			if ((url == null) || url.isEmpty()) return null;
			int sec = ps.getInt(KEY_SEC, 0);
			ps.edit().clear().commit();
			return new Link(url, sec);
		}
	}

	@Nullable
	public static Link parse(@Nullable String text) {
		if ((text == null) || text.isEmpty()) return null;
		int http = indexOfIgnoreCase(text, "http");
		if (http < 0) return null;
		int end = http;
		while ((end < text.length()) && !Character.isWhitespace(text.charAt(end))) end++;
		String url = stripTail(text.substring(http, end));
		String id = videoId(url);
		if (id.isEmpty()) return null;
		return new Link("https://www.youtube.com/watch?v=" + id, startSeconds(url));
	}

	static String videoId(String url) {
		String lower = url.toLowerCase(java.util.Locale.US);
		int be = lower.indexOf("youtu.be/");
		if (be >= 0) return idToken(url, be + "youtu.be/".length());
		int shorts = lower.indexOf("/shorts/");
		if (shorts >= 0) return idToken(url, shorts + "/shorts/".length());
		int live = lower.indexOf("/live/");
		if (live >= 0) return idToken(url, live + "/live/".length());
		int v = query(url, "v");
		if (v >= 0) return idToken(url, v);
		return "";
	}

	static int startSeconds(String url) {
		int t = query(url, "t");
		if (t < 0) t = query(url, "start");
		if (t < 0) return 0;
		return parseClock(url.substring(t));
	}

	private static int query(String url, String name) {
		int hash = url.indexOf('#');
		String base = hash < 0 ? url : url.substring(0, hash);
		int q = base.indexOf('?');
		if (q < 0) return -1;
		String[] parts = base.substring(q + 1).split("&");
		for (String part : parts) {
			int eq = part.indexOf('=');
			String key = eq < 0 ? part : part.substring(0, eq);
			if (key.equals(name)) return eq < 0 ? part.length() : (base.indexOf(part) + eq + 1);
		}
		return -1;
	}

	private static String idToken(String url, int from) {
		int i = from;
		while (i < url.length()) {
			char c = url.charAt(i);
			if (((c >= 'A') && (c <= 'Z')) || ((c >= 'a') && (c <= 'z')) || ((c >= '0') && (c <= '9')) ||
					(c == '_') || (c == '-')) {
				i++;
			} else break;
		}
		if (i - from < 6) return "";
		return url.substring(from, i);
	}

	static int parseClock(String raw) {
		int end = 0;
		while ((end < raw.length()) && (raw.charAt(end) != '&') && (raw.charAt(end) != '#')) end++;
		String s = raw.substring(0, end).toLowerCase(java.util.Locale.US);
		if (s.isEmpty()) return 0;
		int total = 0;
		int n = 0;
		boolean any = false;
		boolean unit = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= '0') && (c <= '9')) {
				n = n * 10 + (c - '0');
				any = true;
				continue;
			}
			if (c == 'h') {
				total += n * 3600;
				n = 0;
				unit = true;
			} else if (c == 'm') {
				total += n * 60;
				n = 0;
				unit = true;
			} else if (c == 's') {
				total += n;
				n = 0;
				unit = true;
			}
		}
		if (!unit) return any ? n : 0;
		return total + n;
	}

	private static String stripTail(String url) {
		int i = url.length();
		while (i > 0) {
			char c = url.charAt(i - 1);
			if ((c == '.') || (c == ',') || (c == ')') || (c == ']') || (c == '"') || (c == '\'')) i--;
			else break;
		}
		return url.substring(0, i);
	}

	private static int indexOfIgnoreCase(String text, String needle) {
		return text.toLowerCase(java.util.Locale.US).indexOf(needle);
	}
}
