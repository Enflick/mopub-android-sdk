// Copyright 2018-2019 Twitter, Inc.
// Licensed under the MoPub SDK License Agreement
// http://www.mopub.com/legal/sdk-license-agreement/

package com.mopub.mraid;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.View;

import com.mopub.common.AdReport;
import com.mopub.common.ExternalViewabilitySessionManager;
import com.mopub.common.VisibleForTesting;
import com.mopub.common.logging.MoPubLog;
import com.mopub.mobileads.AdViewController;
import com.mopub.mobileads.CustomEventBanner;
import com.mopub.mobileads.InternalCustomEventBannerListener;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.factories.MraidControllerFactory;
import com.mopub.mraid.MraidController.MraidListener;

import java.util.Map;

import static com.mopub.common.DataKeys.AD_REPORT_KEY;
import static com.mopub.common.DataKeys.BANNER_IMPRESSION_PIXEL_COUNT_ENABLED;
import static com.mopub.common.DataKeys.HTML_RESPONSE_BODY_KEY;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CLICKED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.CUSTOM;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_ATTEMPTED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_FAILED;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.LOAD_SUCCESS;
import static com.mopub.common.logging.MoPubLog.AdapterLogEvent.SHOW_ATTEMPTED;
import static com.mopub.common.util.JavaScriptWebViewCallbacks.WEB_VIEW_DID_APPEAR;
import static com.mopub.mobileads.MoPubErrorCode.MRAID_LOAD_ERROR;

class MraidBanner extends CustomEventBanner {
    public static final String ADAPTER_NAME = MraidBanner.class.getSimpleName();

    @Nullable private MraidController mMraidController;
    @Nullable private InternalCustomEventBannerListener mBannerListener;
    @Nullable private MraidWebViewDebugListener mDebugListener;
    @Nullable private ExternalViewabilitySessionManager mExternalViewabilitySessionManager;
    private boolean mBannerImpressionPixelCountEnabled = false;

    @Override
    protected void loadBanner(@NonNull final Context context,
                    @NonNull final CustomEventBannerListener customEventBannerListener,
                    @NonNull final Map<String, Object> localExtras,
                    @NonNull final Map<String, String> serverExtras) {
        try {
            mBannerListener = (InternalCustomEventBannerListener) customEventBannerListener;
        } catch (ClassCastException e) {
            MoPubLog.log(LOAD_FAILED, ADAPTER_NAME,
                    MRAID_LOAD_ERROR.getIntCode(),
                    MRAID_LOAD_ERROR);
            customEventBannerListener.onBannerFailed(MRAID_LOAD_ERROR);
            return;
        }

        MoPubLog.log(LOAD_ATTEMPTED, ADAPTER_NAME);

        String htmlData;
        if (extrasAreValid(serverExtras)) {
            htmlData = serverExtras.get(HTML_RESPONSE_BODY_KEY);
        } else {
            MoPubLog.log(LOAD_FAILED, ADAPTER_NAME,
                    MRAID_LOAD_ERROR.getIntCode(),
                    MRAID_LOAD_ERROR);
            mBannerListener.onBannerFailed(MRAID_LOAD_ERROR);
            return;
        }

        final Object bannerImpressionPixelCountEnabledObject = localExtras.get(
                BANNER_IMPRESSION_PIXEL_COUNT_ENABLED);
        if (bannerImpressionPixelCountEnabledObject instanceof Boolean) {
            mBannerImpressionPixelCountEnabled = (boolean) bannerImpressionPixelCountEnabledObject;
        }

        try {
            AdReport adReport = (AdReport) localExtras.get(AD_REPORT_KEY);
            mMraidController = MraidControllerFactory.create(
                    context, adReport, PlacementType.INLINE);
        } catch (ClassCastException e) {
            MoPubLog.log(LOAD_FAILED, ADAPTER_NAME,
                    MRAID_LOAD_ERROR.getIntCode(),
                    MRAID_LOAD_ERROR);
            mBannerListener.onBannerFailed(MRAID_LOAD_ERROR);
            return;
        }

        mMraidController.setDebugListener(mDebugListener);
        mMraidController.setMraidListener(new MraidListener() {
            @Override
            public void onLoaded(View view) {
                // Honoring the server dimensions forces the WebView to be the size of the banner
                AdViewController.setShouldHonorServerDimensions(view);
                MoPubLog.log(LOAD_SUCCESS, ADAPTER_NAME);
                MoPubLog.log(SHOW_ATTEMPTED, ADAPTER_NAME);
                mBannerListener.onBannerLoaded(view);
            }

            @Override
            public void onFailedToLoad() {
                MoPubLog.log(LOAD_FAILED, ADAPTER_NAME,
                        MRAID_LOAD_ERROR.getIntCode(),
                        MRAID_LOAD_ERROR);
                mBannerListener.onBannerFailed(MRAID_LOAD_ERROR);
            }

            @Override
            public void onRenderProcessGone(@NonNull final MoPubErrorCode errorCode) {
                MoPubLog.log(LOAD_FAILED, ADAPTER_NAME, errorCode.getIntCode(), errorCode);
                mBannerListener.onBannerFailed(errorCode);
            }

            @Override
            public void onExpand() {
                mBannerListener.onBannerExpanded();
                mBannerListener.onBannerClicked();
            }

            @Override
            public void onResize(final boolean toOriginalSize) {
                if (toOriginalSize) {
                    mBannerListener.onResumeAutoRefresh();
                } else {
                    mBannerListener.onPauseAutoRefresh();
                }
            }

            @Override
            public void onOpen() {
                MoPubLog.log(CLICKED, ADAPTER_NAME);
                mBannerListener.onBannerClicked();
            }

            @Override
            public void onClose() {
                mBannerListener.onBannerCollapsed();
            }
        });

        mMraidController.fillContent(htmlData, new MraidController.MraidWebViewCacheListener() {
            @Override
            public void onReady(final @NonNull MraidBridge.MraidWebView webView,
                    final @Nullable ExternalViewabilitySessionManager viewabilityManager) {
                webView.getSettings().setJavaScriptEnabled(true);

                // We only measure viewability when we have an activity context. This sets up a delayed
                // viewability session if we have the new pixel-counting banner impression tracking enabled.
                // Otherwise, set up a regular display session.
                if (context instanceof Activity) {
                    mExternalViewabilitySessionManager = new ExternalViewabilitySessionManager(
                            context);
                    mExternalViewabilitySessionManager.createDisplaySession(context, webView,
                            mBannerImpressionPixelCountEnabled);
                }
            }
        });
    }

    @Override
    protected void onInvalidate() {
        if (mExternalViewabilitySessionManager != null) {
            mExternalViewabilitySessionManager.endDisplaySession();
            mExternalViewabilitySessionManager = null;
        }
        if (mMraidController != null) {
            mMraidController.setMraidListener(null);
            mMraidController.destroy();
        }
    }

    @Override
    protected void trackMpxAndThirdPartyImpressions() {
        if (mMraidController == null) {
            return;
        }

        mMraidController.loadJavascript(WEB_VIEW_DID_APPEAR.getJavascript());

        // mExternalViewabilitySessionManager is usually only null if the original Context given
        // to mMraidController was not an Activity Context. We don't need to start the deferred
        // viewability tracker since it wasn't created, and if it was, and the activity reference
        // was lost, something bad has happened, so we should drop the request.
        if (mBannerImpressionPixelCountEnabled &&
                mExternalViewabilitySessionManager != null) {
            final Activity activity = mMraidController.getWeakActivity().get();
            if (activity != null) {
                mExternalViewabilitySessionManager.startDeferredDisplaySession(activity);
            } else {
                MoPubLog.log(CUSTOM, ADAPTER_NAME, "Lost the activity for deferred Viewability tracking. Dropping session.");
            }
        }
    }

    private boolean extrasAreValid(@NonNull final Map<String, String> serverExtras) {
        return serverExtras.containsKey(HTML_RESPONSE_BODY_KEY);
    }

    @VisibleForTesting
    public void setDebugListener(@Nullable MraidWebViewDebugListener debugListener) {
        mDebugListener = debugListener;
        if (mMraidController != null) {
            mMraidController.setDebugListener(debugListener);
        }
    }

    @VisibleForTesting
    boolean isBannerImpressionPixelCountEnabled() {
        return mBannerImpressionPixelCountEnabled;
    }
}
