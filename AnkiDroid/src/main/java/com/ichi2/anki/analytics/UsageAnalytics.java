/****************************************************************************************
 * Copyright (c) 2018 Mike Hardy <mike@mikehardy.net>                                   *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki.analytics;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.brsanthu.googleanalytics.GoogleAnalytics;
import com.brsanthu.googleanalytics.GoogleAnalyticsConfig;
import com.brsanthu.googleanalytics.httpclient.OkHttpClientImpl;
import com.brsanthu.googleanalytics.request.DefaultRequest;
import com.brsanthu.googleanalytics.request.EventHit;
import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.anki.BuildConfig;
import com.ichi2.anki.R;

import org.acra.util.Installation;

import androidx.annotation.NonNull;
import timber.log.Timber;

public class UsageAnalytics {

    public static final String ANALYTICS_OPTIN_KEY = "analyticsOptIn";
    private static GoogleAnalytics sAnalytics;
    private static Thread.UncaughtExceptionHandler sOriginalUncaughtExceptionHandler;
    private static boolean sOptIn = true;


    /**
     * Initialize the analytics provider - must be called prior to sending anything.
     * Usage after that is static
     * Note: may need to implement sampling strategy internally to limit hits, or not track Reviewer...
     *
     * @param context required to look up the analytics codes for the app
     */
    synchronized public static GoogleAnalytics initialize(Context context) {
        Timber.i("initialize()");
        if (sAnalytics == null) {
            Timber.d("tid = %s", context.getString(R.string.ga_trackingId));
            GoogleAnalyticsConfig gaConfig = new GoogleAnalyticsConfig()
                    .setBatchingEnabled(true)
                    .setBatchSize(1); // until this handles application termination we will lose hits
            sAnalytics = GoogleAnalytics.builder()
                    .withTrackingId(context.getString(R.string.ga_trackingId))
                    .withConfig(gaConfig)
                    .withDefaultRequest(new DefaultRequest()
                            .applicationName(context.getString(R.string.app_name))
                            .applicationVersion(Integer.toString(BuildConfig.VERSION_CODE))
                            .applicationId(BuildConfig.APPLICATION_ID)
                            .trackingId(context.getString(R.string.ga_trackingId))
                            .clientId(Installation.id(context))
                            .anonymizeIp(Boolean.getBoolean(context.getString(R.string.ga_anonymizeIp)))
                    )
                    .withHttpClient(new OkHttpClientImpl(gaConfig))
                    .build();
        }

        installDefaultExceptionHandler();

        SharedPreferences userPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        setOptIn(userPrefs.getBoolean(ANALYTICS_OPTIN_KEY, false));
        userPrefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if (key.equals(ANALYTICS_OPTIN_KEY)) {
                setOptIn(sharedPreferences.getBoolean(key, false));
                reInitialize();
            }
        });

        return sAnalytics;
    }


    /**
     * We want to send an analytics hit on any exception, then chain to other handlers (e.g., ACRA)
     */
    synchronized private static void installDefaultExceptionHandler() {
        sOriginalUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            sendAnalyticsException(throwable, true);
            sOriginalUncaughtExceptionHandler.uncaughtException(thread, throwable);
        });
    }


    /**
     * Reset the default exception handler
     */
    synchronized private static void unInstallDefaultExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(sOriginalUncaughtExceptionHandler);
        sOriginalUncaughtExceptionHandler = null;
    }


    /**
     * Allow users to enable or disable analytics
     *
     * @param optIn true allows collection of analytics information
     */
    synchronized private static void setOptIn(boolean optIn) {
        Timber.i("setOptIn(): from %s to %s", sOptIn, optIn);
        sOptIn = optIn;
        sAnalytics.getConfig().setEnabled(optIn);
    }


    /**
     * Set the analytics up to log things, goes to hit validator. Experimental.
     *
     * @param dryRun set to true if you want to log analytics hit but not dispatch
     */
    synchronized public static void setDryRun(boolean dryRun) {
        Timber.i("setDryRun(): %s, warning dryRun is experimental", dryRun);
    }


    /**
     * Re-Initialize the analytics provider
     */
    synchronized public static void reInitialize() {

        // send any pending async hits, re-chain default exception handlers and re-init
        Timber.i("reInitialize()");
        sAnalytics.flush();
        sAnalytics = null;
        unInstallDefaultExceptionHandler();
        initialize(AnkiDroidApp.getInstance().getApplicationContext());
    }


    /**
     * Submit a screen for aggregation / analysis.
     * Intended for use to determine if / how features are being used
     *
     * @param object the result of Object.getClass().getSimpleName() will be used as the screen tag
     */
    public static void sendAnalyticsScreenView(Object object) {
        sendAnalyticsScreenView(object.getClass().getSimpleName());
    }


    /**
     * Submit a screen display with a synthetic name for aggregation / analysis
     * Intended for use if your class handles multiple screens you want to track separately
     *
     * @param screenName screenName the name to show in analysis reports
     */
    public static void sendAnalyticsScreenView(String screenName) {
        Timber.d("sendAnalyticsScreenView(): %s", screenName);
        sAnalytics.screenView().screenName(screenName).sendAsync();
    }


    /**
     * Send an arbitrary analytics event - these should be noun/verb pairs, e.g. "text to speech", "enabled"
     *
     * @param category the category of event, make your own but use a constant so reporting is good
     * @param action the action the user performed
     */
    public static void sendAnalyticsEvent(@NonNull String category, @NonNull String action) {
        sendAnalyticsEvent(category, action, Integer.MIN_VALUE, null);
    }


    /**
     * Send a detailed arbitrary analytics event, with noun/verb pairs and extra data if needed
     *
     * @param category the category of event, make your own but use a constant so reporting is good
     * @param action the action the user performed
     * @param value A value for the event, Integer.MIN_VALUE signifies caller shouldn't send the value
     * @param label A label for the event, may be null
     */
    @SuppressWarnings("WeakerAccess")
    public static void sendAnalyticsEvent(@NonNull String category, @NonNull String action, int value, String label) {
        Timber.d("sendAnalyticsEvent() category/action/value/label: %s/%s/%s/%s", category, action, value, label);
        EventHit event = sAnalytics.event().eventCategory(category).eventAction(action);
        if (label != null) {
            event.eventLabel(label);
        }
        if (value > Integer.MIN_VALUE) {
            event.eventValue(value);
        }
        event.sendAsync();
    }


    /**
     * Send an exception event out for aggregation/analysis, parsed from the exception information
     *
     * @param t Throwable to send for analysis
     * @param fatal whether it was fatal or not
     */
    public static void sendAnalyticsException(@NonNull Throwable t, boolean fatal) {
        sendAnalyticsException(getCause(t).toString(), fatal);
    }


    public static Throwable getCause(Throwable t) {
        Throwable cause;
        Throwable result = t;

        while (null != (cause = result.getCause())  && (!result.equals(cause)) ) {
            result = cause;
        }
        return result;
    }


    /**
     * Send an exception event out for aggregation/analysis
     *
     * @param description API limited to 100 characters, truncated here to 100 if needed
     * @param fatal whether it was fatal or not
     */
    @SuppressWarnings("WeakerAccess")
    public static void sendAnalyticsException(@NonNull String description, boolean fatal) {
        Timber.d("sendAnalyticsException() description/fatal: %s/%s", description, fatal);
        sAnalytics.exception().exceptionDescription(description).exceptionFatal(fatal).sendAsync();
    }
}
