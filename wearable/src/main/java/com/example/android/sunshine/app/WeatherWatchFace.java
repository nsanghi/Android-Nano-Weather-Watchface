/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService  {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mTextPaintDate;
        Paint mTextPaintTemp;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        private GoogleApiClient mGoogleApiClient;

        public final String LOG_TAG = Engine.class.getSimpleName();

        private String mHigh;
        private String mLow;
        private Bitmap mIcon;

        private static final int MARGIN = 10;

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "in onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);

        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "in onDataChanged");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = dataEvent.getDataItem();
                    String path = item.getUri().getPath();
                    Log.d(LOG_TAG, "Data Item path:" + path);
                    if (path.compareTo("/weather") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        if (dataMap.containsKey("HIGH")) {
                            mHigh = dataMap.getString("HIGH");
                            Log.d(LOG_TAG, "HIgh=" + mHigh);
                        } else {
                            Log.d(LOG_TAG, "key=HIGH not found on dataMap");
                        }
                        if (dataMap.containsKey("LOW")) {
                            mLow = dataMap.getString("LOW");
                            Log.d(LOG_TAG, "Low=" + mLow);
                        } else {
                            Log.d(LOG_TAG, "key=LOW not found on dataMap");
                        }
                        if (dataMap.containsKey("ICON")) {
                            Asset asset = dataMap.getAsset("ICON");
                            DownloadFilesTask task = new DownloadFilesTask();
                            task.execute(asset);
                        } else {
                            Log.d(LOG_TAG, "key=ICON not found on dataMap");
                        }
                        invalidate();
                    }
                }
            }

        }


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine_primary));

            int color = resources.getColor(R.color.digital_text);
            mTextPaint = createTextPaint(color);
            mTextPaintDate = createTextPaint(color);
            mTextPaintTemp = createTextPaint(color);

            int fontTime = resources.getDimensionPixelSize(R.dimen.digital_time_size);
            int fontDate = resources.getDimensionPixelSize(R.dimen.digital_date_size);
            int fontTemp = resources.getDimensionPixelSize(R.dimen.digital_temperature_size);


            mTextPaint.setTextSize(fontTime);
            mTextPaintDate.setTextSize(fontDate);
            mTextPaintTemp.setTextSize(fontTemp);


            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            Log.d(LOG_TAG, "in onVisibilityChanged");

            if (visible) {
                registerReceiver();
                Log.d(LOG_TAG, "Trying to connect to Google API");
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                    mTextPaintTemp.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            String dateFormat = "EEE, MMM d yyyy";
            SimpleDateFormat dt = new SimpleDateFormat(dateFormat);
            String date = dt.format(new Date());
            float dateWidth = mTextPaintDate.measureText(date);
            int posXDate = bounds.centerX() - (int)(dateWidth/2.0);
            int posYDate = bounds.centerY() - (int)(mTextPaintDate.getTextSize()/2.0);
            canvas.drawText(date, posXDate, posYDate, mTextPaintDate);

            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            float timeWidth = mTextPaint.measureText(text);
            int posX = bounds.centerX() - (int)(timeWidth/2.0);
            int posY = bounds.centerY()
                    - (int)(mTextPaintDate.getTextSize()/2.0)
                    -(int)(mTextPaint.getTextSize()/2.0)
                    - MARGIN;
            canvas.drawText(text, posX, posY, mTextPaint);

            if (mHigh != null && mLow != null) {
                String temp = mHigh + " " + mLow;
                float tempWidth = mTextPaintTemp.measureText(temp);
                int posTempX = bounds.centerX() - (int)(tempWidth/2.0) + MARGIN*3;
                int posTempY = bounds.centerY()
                        + (int)(mTextPaintDate.getTextSize()/2.0)
                        +(int)(mTextPaintTemp.getTextSize()/2.0)
                        + MARGIN;
                canvas.drawText(temp, posTempX, posTempY, mTextPaintTemp);

                if (!isInAmbientMode()&& mIcon != null) {
                    canvas.drawBitmap(mIcon,
                            posTempX - mIcon.getWidth() - MARGIN,
                            posTempY-mTextPaintTemp.getTextSize()/2-mIcon.getHeight()/2+MARGIN, null);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        private class DownloadFilesTask extends AsyncTask<Asset, Void, Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... params) {
                // Log.v("SunshineWatchFace", "Doing Background");
                return loadBitmapFromAsset(params[0]);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                mIcon = Bitmap.createScaledBitmap(b, 60, 60, false);
            }

            public Bitmap loadBitmapFromAsset(Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                if (mGoogleApiClient == null || !mGoogleApiClient.isConnected()) {
                    return null;
                }

                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();
                //mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(LOG_TAG, "Requested an unknown Asset.");
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

        }
    }
}

