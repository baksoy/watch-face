package com.baksoy.wearable;

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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String LOG_TAG = SunshineWatchFaceService.class.getSimpleName();

    /**
     * Interactive mode update rate in milliseconds.
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
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        //Member variables
        private Calendar mCalendar;
        private Date mDate;
        private SimpleDateFormat mDayOfWeekFormat;
        private java.text.DateFormat mDateFormat;

        private final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        private final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

        private Bitmap mWeatherIcon;

        private float mLineHeight;
        private static final String COLON_STRING = ":";
        private static final int TEXT_DATE_COLOR = Color.GRAY;
        private static final int TEXT_COLON_COLOR = Color.GRAY;
        private static final int TEXT_HOURS_MINS_COLOR = Color.WHITE;
        private static final int TEXT_SECONDS_COLOR = Color.WHITE;
        private final Typeface WATCH_TEXT_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

        private static final long DEFAULT_UPDATE_RATE_MS = 1000;
        private long mUpdateRateMs = 1000;

        private Paint mBackgroundColorPaint;
        private Paint mTextColorPaint;
        private Paint mHourPaint;
        private Paint mColonPaint;
        private Paint mMinutePaint;
        private Paint mSecondPaint;
        private Paint mTemperaturePaint;

        private boolean mHasTimeZoneReceiverBeenRegistered = false;
        private boolean mIsInMuteMode;
        private boolean mIsLowBitAmbient;

        private float mXOffset;
        private float mColonWidth;

        private final int mTextColor = ContextCompat.getColor(getApplicationContext(), R.color.text_color);
        private final int mBackgroundColor = ContextCompat.getColor(getApplicationContext(), R.color.background_color);
        private final int temperatureColor = ContextCompat.getColor(getApplicationContext(), R.color.temperature_color);
        private final int mTextColorAmbient = ContextCompat.getColor(getApplicationContext(), R.color.text_color_ambient);
        private final int mBackgroundColorAmbient = ContextCompat.getColor(getApplicationContext(), R.color.background_color_ambient);

        private boolean mRegisteredTimeZoneReceiver = false;

        private Bitmap mIcon;

        private Paint mDatePaint;
        private Paint mIconPaint;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mYOffset;

        String mLowTemp = "";
        String mHighTemp = "";

        private GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            Resources resources = SunshineWatchFaceService.this.getResources();

            //set how the system interacts with the user when the watch face is active
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                            .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                            .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                            .setShowSystemUiTime(false)
                            .setHotwordIndicatorGravity(Gravity.LEFT)
                            .setStatusBarGravity(Gravity.RIGHT)
                            .build()
            );

            // initialize the background
            initBackground();

            // initialize the display text
            initDisplayText();

            //Draw the Weather Icon
            Drawable weatherDraw = resources.getDrawable(R.mipmap.weather_icon, null);
            mWeatherIcon = ((BitmapDrawable) weatherDraw) != null ? ((BitmapDrawable) weatherDraw).getBitmap() : null;
            mIconPaint = new Paint();

            mYOffset = resources.getDimension(R.dimen.fit_y_offset);
            mLineHeight = resources.getDimension(R.dimen.fit_line_height);

            mDatePaint = createTextPaint(TEXT_DATE_COLOR);
            mHourPaint = createTextPaint(TEXT_HOURS_MINS_COLOR, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(TEXT_HOURS_MINS_COLOR, BOLD_TYPEFACE);
            mSecondPaint = createTextPaint(TEXT_SECONDS_COLOR);
            mColonPaint = createTextPaint(TEXT_COLON_COLOR);
            mTemperaturePaint = createTextPaint(temperatureColor);

            //load background image
            //Drawable backgroundDrawable = resources.getDrawable(R.drawable.bg, null);
            //mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
            Log.d(LOG_TAG, "GoogleApiClient Connected");
        }

        private void initBackground() {
            mBackgroundColorPaint = new Paint();
            mBackgroundColorPaint.setColor(mBackgroundColor);
        }

        private void initDisplayText() {
            mTextColorPaint = new Paint();
            mTextColorPaint.setColor(mTextColor);
            mTextColorPaint.setTypeface(WATCH_TEXT_TYPEFACE);
            mTextColorPaint.setAntiAlias(true);
            mTextColorPaint.setTextSize(getResources().getDimension(R.dimen.text_size));
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getDateFormat(SunshineWatchFaceService.this);
            mDateFormat.setCalendar(mCalendar);
        }

        private Paint createTextPaint(int color) {
            return createTextPaint(color, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int color, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        //This method is called when the user hides or shows the watch interface
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            //check if watch face is visible
            if (visible) {
                //check if TimeZone has been registered
                //if not registered, register it and listen for any time zone changes
                if (!mHasTimeZoneReceiverBeenRegistered) {
                    IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
                    SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
                    mHasTimeZoneReceiverBeenRegistered = true;
                }
                long now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(now);
            } else {
                //if not visible and has been registered, then unregister it
                if (mHasTimeZoneReceiverBeenRegistered) {
                    SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
                    mHasTimeZoneReceiverBeenRegistered = false;
                }
            }
            updateTimer();
        }

        /**
         * Called when the user manually changes the interruption settings on the wearable
         **/
        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean isDeviceMuted = (interruptionFilter == android.support.wearable.watchface.WatchFaceService.INTERRUPTION_FILTER_NONE);

            if (isDeviceMuted) {
                mUpdateRateMs = TimeUnit.MINUTES.toMillis(1);
            } else {
                mUpdateRateMs = DEFAULT_UPDATE_RATE_MS;
            }
            if (mIsInMuteMode != isDeviceMuted) {
                mIsInMuteMode = isDeviceMuted;
                int alpha = (isDeviceMuted) ? 100 : 255;
                mTextColorPaint.setAlpha(alpha);
                invalidate();
                updateTimer();
            }
        }


        /**
         * Update the current time every minute by using the built-in
         * onTimeTick method to invalidate the Canvas.
         * Called periodically to update the time shown by the watch face.
         * at least once per minute in both ambient and interactive modes
         **/
        @Override
        public void onTimeTick() {
            super.onTimeTick();
            //invalidate the canvas
            invalidate();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Called when your service is associated with Android Wear
         * and determines if your watch face is Rounded or Square
         * This lets you change your watch face to match up with the hardware
         **/
        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mYOffset = getResources().getDimension(R.dimen.y_offset);
            if (insets.isRound()) {
                mXOffset = getResources().getDimension(R.dimen.x_offset_round);
            } else {
                mXOffset = getResources().getDimension(R.dimen.x_offset_square);
            }

            float textSize = resources.getDimension(isRound
                    ? R.dimen.fit_text_size_round : R.dimen.fit_text_size);

            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mSecondPaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.fit_date_text_size));
            mTemperaturePaint.setTextSize(resources.getDimension(R.dimen.fit_temperature_text_size));
            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        /**
         * Called when the hardware properties for the Wear device are determined
         * eg. if the device supports burn-in protection or low bit ambient mode
         **/
        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            if (properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false)) {
                mIsLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
                mHourPaint.setTypeface(NORMAL_TYPEFACE);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        /**
         * Called when the device moves in and out of Ambient mode
         **/
        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (inAmbientMode) {
                mTextColorPaint.setColor(mTextColorAmbient);
                mHourPaint.setTypeface(NORMAL_TYPEFACE);
                mMinutePaint.setTypeface(NORMAL_TYPEFACE);
                mBackgroundColorPaint.setColor(mBackgroundColorAmbient);
                mDatePaint.setColor(mBackgroundColorAmbient);
                mTemperaturePaint.setColor(mBackgroundColorAmbient);
                mIconPaint.setAlpha(0);
            } else {
                mTextColorPaint.setColor(mTextColor);
                mHourPaint.setTypeface(BOLD_TYPEFACE);
                mMinutePaint.setTypeface(BOLD_TYPEFACE);
                mBackgroundColorPaint.setColor(mBackgroundColor);
                mDatePaint.setColor(TEXT_DATE_COLOR);
                mTemperaturePaint.setColor(temperatureColor);
                mIconPaint.setAlpha(255);
            }

            if (mIsLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mTextColorPaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(!antiAlias);
                mSecondPaint.setAntiAlias(!antiAlias);
                mDatePaint.setAntiAlias(!antiAlias);
            }
            invalidate();
            updateTimer();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            drawBackground(canvas, bounds);

            float x = mXOffset;

            // Draw the Date
            canvas.drawText(mDateFormat.format(mDate), 105, 60 + mLineHeight, mDatePaint);

            // Draw the Hour
            String hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // Draw first colon (between hour and minute).
            canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);

            x += mColonWidth;

            // Draw the Minute
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            //Get center
            float centerX = bounds.centerX();
            float centerY = bounds.centerY();

            //Draw the Temp
            canvas.drawText(mHighTemp + mLowTemp, 90, 230, mTemperaturePaint);
            Log.d(LOG_TAG, "High Temp:" + mHighTemp);
            Log.d(LOG_TAG, "Low Temp:" + mLowTemp);

            float dateYOffset = mYOffset + getResources().getDimension(R.dimen.digital_time_text_margin_bottom);

            //Icon
            if (mIcon != null && !mLowBitAmbient)
                canvas.drawBitmap(mIcon, 175, 185, mIconPaint);
        }

        private void drawBackground(Canvas canvas, Rect bounds) {
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundColorPaint);
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

        /**
         * GoogleApiClient implementation
         */
        @Override
        public void onConnected(Bundle bundle) {
            Log.d(LOG_TAG, "Connected to Google Play" + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onConnectionFailed(ConnectionResult cause) {
            Log.d(LOG_TAG, "onConnectionFailed: " + cause);
        }

        /**
         * DataApi listener
         */
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "New data received");

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {

                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String path = event.getDataItem().getUri().getPath();

                    if (path.equals("/weather-data")) {
                        mHighTemp = dataMap.getString("high-temp");
                        mLowTemp = dataMap.getString("low-temp");
                        new GetBitmapForWeatherTask().execute(dataMap.getAsset("icon"));
                        invalidate();
                    }
                }
            }
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        public class GetBitmapForWeatherTask extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... assets) {
                Asset asset = assets[0];
                mIcon = loadBitmapFromAsset(asset);

                int size = Double.valueOf(SunshineWatchFaceService.this.getResources().getDimension(R.dimen.digital_icon_size)).intValue();
                mIcon = Bitmap.createScaledBitmap(mIcon, size, size, false);
                postInvalidate();

                return null;
            }
        }
    }
}
