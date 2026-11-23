package me.aap.fermata.addon.web.yt;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewFeature;

import me.aap.fermata.addon.web.FermataWebClient;
import me.aap.fermata.addon.web.WebBrowserFragment;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * @author Andrey Pavlenko
 */
public class YoutubeWebClient extends FermataWebClient {

	@Override
	public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
		// Sign-in and the activate code stay inside this page. The browser fragment would drop them.
		if ((view instanceof YoutubeWebView web) && web.tvActive()) return false;
		if (!isYoutubeUri(request.getUrl())) {
			MainActivityDelegate a = MainActivityDelegate.get(view.getContext());

			try {
				if (!(a.showFragment(
						me.aap.fermata.R.id.web_browser_fragment) instanceof WebBrowserFragment f))
					return false;
				f.loadUrl(request.getUrl().toString());
				return true;
			} catch (IllegalArgumentException ex) {
				Log.d(ex);
			}
		}

		return false;
	}

	@Override
	public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
															@NonNull WebResourceErrorCompat error) {
		super.onReceivedError(view, request, error);
		if (!request.isForMainFrame() || !(view instanceof YoutubeWebView web)) return;
		String desc = "error";
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) {
			CharSequence text = error.getDescription();
			if (text != null) desc = text.toString();
		}
		web.onTvLoadError(desc);
	}
}
