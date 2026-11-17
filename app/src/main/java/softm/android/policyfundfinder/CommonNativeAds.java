package softm.android.policyfundfinder;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

final class CommonNativeAds {
    private static final long INTERSTITIAL_FIRST_DELAY_MS = 75_000L;
    private static final long INTERSTITIAL_COOLDOWN_MS = 180_000L;
    private static final int INTERSTITIAL_MAX_PER_SESSION = 3;

    private final Activity activity;
    private final LinearLayout section;
    private final FrameLayout host;
    private final Button privacy;
    private ConsentInformation consent;
    private NativeAd nativeAd;
    private InterstitialAd interstitialAd;
    private AdView preloadedExitAd;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean initialized;
    private boolean interstitialLoading;
    private boolean interstitialShowScheduled;
    private boolean preloadedExitAdReady;
    private FrameLayout pendingExitSlot;
    private Runnable pendingExitResolved;
    private int interstitialShownCount;
    private long appStartedAt;
    private long lastInterstitialShownAt;

    CommonNativeAds(Activity activity, LinearLayout section) {
        this.activity = activity;
        this.section = section;
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Color.WHITE);
        section.setVisibility(View.GONE);
        privacy = new Button(activity);
        privacy.setText("광고 개인정보 설정");
        privacy.setTextSize(11);
        privacy.setVisibility(View.GONE);
        privacy.setOnClickListener(view -> showPrivacyOptions());
        section.addView(privacy, new LinearLayout.LayoutParams(-2, -2));
        host = new FrameLayout(activity);
        section.addView(host, new LinearLayout.LayoutParams(-1, -2));
    }

    void start() {
        appStartedAt = System.currentTimeMillis();
        consent = UserMessagingPlatform.getConsentInformation(activity);
        consent.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().build(), () -> {
            updatePrivacy();
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, error -> {
                updatePrivacy();
                maybeLoad();
            });
            maybeLoad();
        }, error -> {
            updatePrivacy();
            maybeLoad();
        });
    }

    private void maybeLoad() {
        if (initialized || consent == null || !consent.canRequestAds()) return;
        initialized = true;
        MobileAds.initialize(activity, status -> activity.runOnUiThread(() -> {
            loadNativeAd();
            loadInterstitialAd();
            preloadExitBannerAd();
            scheduleInterstitialOpportunity();
        }));
    }

    private void loadNativeAd() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AdLoader.Builder(activity, activity.getString(R.string.admob_native_ad_unit_id))
            .forNativeAd(ad -> {
                if (activity.isFinishing() || activity.isDestroyed()) { ad.destroy(); return; }
                if (nativeAd != null) nativeAd.destroy();
                nativeAd = ad;
                host.removeAllViews();
                host.addView(render(ad), new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ));
                updateVisibility(true);
            })
            .withAdListener(new com.google.android.gms.ads.AdListener() {
                @Override public void onAdFailedToLoad(LoadAdError error) {
                    host.removeAllViews();
                    updateVisibility(false);
                }
            })
            .withNativeAdOptions(new NativeAdOptions.Builder().build())
            .build().loadAd(new AdRequest.Builder().build());
    }

    private void loadInterstitialAd() {
        if (interstitialLoading || interstitialAd != null || !activityAlive()) return;
        interstitialLoading = true;
        InterstitialAd.load(activity, activity.getString(R.string.admob_interstitial_ad_unit_id),
            new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override public void onAdLoaded(InterstitialAd ad) {
                    interstitialLoading = false;
                    interstitialAd = ad;
                    ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                        @Override public void onAdDismissedFullScreenContent() {
                            interstitialAd = null;
                            lastInterstitialShownAt = System.currentTimeMillis();
                            loadInterstitialAd();
                            scheduleInterstitialOpportunity();
                        }

                        @Override public void onAdFailedToShowFullScreenContent(AdError error) {
                            interstitialAd = null;
                            loadInterstitialAd();
                            scheduleInterstitialOpportunity();
                        }
                    });
                    scheduleInterstitialOpportunity();
                }

                @Override public void onAdFailedToLoad(LoadAdError error) {
                    interstitialLoading = false;
                    interstitialAd = null;
                    handler.postDelayed(CommonNativeAds.this::loadInterstitialAd, INTERSTITIAL_COOLDOWN_MS);
                }
            });
    }

    private void scheduleInterstitialOpportunity() {
        if (interstitialShowScheduled || interstitialShownCount >= INTERSTITIAL_MAX_PER_SESSION) return;
        long now = System.currentTimeMillis();
        long firstWait = Math.max(0L, appStartedAt + INTERSTITIAL_FIRST_DELAY_MS - now);
        long cooldownWait = lastInterstitialShownAt == 0L
            ? 0L : Math.max(0L, lastInterstitialShownAt + INTERSTITIAL_COOLDOWN_MS - now);
        interstitialShowScheduled = true;
        handler.postDelayed(() -> {
            interstitialShowScheduled = false;
            showInterstitialIfReady();
        }, Math.max(firstWait, cooldownWait));
    }

    private void showInterstitialIfReady() {
        if (!activityAlive() || interstitialShownCount >= INTERSTITIAL_MAX_PER_SESSION) return;
        if (interstitialAd == null) {
            loadInterstitialAd();
            return;
        }
        interstitialShownCount++;
        interstitialAd.show(activity);
    }

    private void preloadExitBannerAd() {
        if (!activityAlive() || preloadedExitAd != null) return;
        AdView adView = new AdView(activity);
        adView.setAdSize(AdSize.MEDIUM_RECTANGLE);
        adView.setAdUnitId(activity.getString(R.string.admob_exit_banner_ad_unit_id));
        preloadedExitAd = adView;
        preloadedExitAdReady = false;
        adView.setAdListener(new com.google.android.gms.ads.AdListener() {
            @Override public void onAdLoaded() {
                if (!activityAlive() || preloadedExitAd != adView) {
                    adView.destroy();
                    return;
                }
                preloadedExitAdReady = true;
                attachPendingExitBannerAd();
            }

            @Override public void onAdFailedToLoad(LoadAdError error) {
                if (preloadedExitAd == adView) {
                    preloadedExitAd = null;
                    preloadedExitAdReady = false;
                }
                adView.destroy();
                if (pendingExitSlot != null) {
                    pendingExitSlot.removeAllViews();
                    pendingExitSlot.setVisibility(View.GONE);
                }
                resolvePendingExitRequest();
                handler.postDelayed(CommonNativeAds.this::preloadExitBannerAd, INTERSTITIAL_COOLDOWN_MS);
            }
        });
        adView.loadAd(new AdRequest.Builder().build());
    }

    boolean attachExitBannerAd(FrameLayout slot, Runnable onResolved) {
        if (!initialized || !activityAlive()) return false;
        pendingExitSlot = slot;
        pendingExitResolved = onResolved;
        if (!preloadedExitAdReady || preloadedExitAd == null) {
            preloadExitBannerAd();
            return true;
        }
        attachPendingExitBannerAd();
        return true;
    }

    private void attachPendingExitBannerAd() {
        FrameLayout slot = pendingExitSlot;
        if (slot == null || !preloadedExitAdReady || preloadedExitAd == null) return;
        AdView adView = preloadedExitAd;
        preloadedExitAd = null;
        preloadedExitAdReady = false;
        slot.removeAllViews();
        slot.addView(adView, new FrameLayout.LayoutParams(dp(300), dp(250), Gravity.CENTER));
        slot.setVisibility(View.VISIBLE);
        resolvePendingExitRequest();
    }

    private void resolvePendingExitRequest() {
        Runnable resolved = pendingExitResolved;
        pendingExitSlot = null;
        pendingExitResolved = null;
        if (resolved != null) resolved.run();
    }

    void onExitDialogDismissed(FrameLayout slot) {
        if (pendingExitSlot == slot) {
            pendingExitSlot = null;
            pendingExitResolved = null;
        }
        for (int index = 0; index < slot.getChildCount(); index++) {
            View child = slot.getChildAt(index);
            if (child instanceof AdView) ((AdView) child).destroy();
        }
        slot.removeAllViews();
        if (activityAlive()) preloadExitBannerAd();
    }

    private boolean activityAlive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private NativeAdView render(NativeAd ad) {
        NativeAdView view = new NativeAdView(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView label = new TextView(activity);
        label.setText("광고");
        label.setTextColor(Color.rgb(73, 112, 94));
        label.setTextSize(11);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMarginEnd(dp(10));
        row.addView(label, labelParams);
        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(ad.getHeadline());
        title.setTextColor(Color.rgb(27, 43, 41));
        title.setTextSize(14);
        title.setMaxLines(1);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = new TextView(activity);
        if (ad.getBody() == null) {
            body.setVisibility(View.GONE);
        } else {
            body.setText(ad.getBody());
            body.setTextColor(Color.rgb(104, 124, 116));
            body.setTextSize(11);
            body.setMaxLines(1);
            view.setBodyView(body);
        }
        copy.addView(body, new LinearLayout.LayoutParams(-1, -2));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button action = new Button(activity);
        if (ad.getCallToAction() == null) {
            action.setVisibility(View.GONE);
        } else {
            action.setText(ad.getCallToAction());
            view.setCallToActionView(action);
        }
        action.setTextSize(12);
        row.addView(action, new LinearLayout.LayoutParams(-2, dp(44)));
        view.addView(row, new FrameLayout.LayoutParams(-1, -2));
        view.setHeadlineView(title);
        view.setNativeAd(ad);
        return view;
    }

    private void updatePrivacy() {
        boolean required = consent != null && consent.getPrivacyOptionsRequirementStatus()
            == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        privacy.setVisibility(required ? View.VISIBLE : View.GONE);
        updateVisibility(host.getChildCount() > 0);
    }

    private void updateVisibility(boolean hasAd) {
        section.setVisibility(hasAd || privacy.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {
            if (error != null) Toast.makeText(activity, "광고 개인정보 설정을 열지 못했습니다.", Toast.LENGTH_SHORT).show();
            updatePrivacy();
            maybeLoad();
        });
    }

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        if (nativeAd != null) nativeAd.destroy();
        nativeAd = null;
        interstitialAd = null;
        if (preloadedExitAd != null) preloadedExitAd.destroy();
        preloadedExitAd = null;
        pendingExitSlot = null;
        pendingExitResolved = null;
        host.removeAllViews();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

