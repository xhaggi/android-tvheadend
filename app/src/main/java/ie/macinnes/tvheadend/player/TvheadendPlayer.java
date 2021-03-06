/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ie.macinnes.tvheadend.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.PlaybackParams;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.tvprovider.media.tv.TvContractCompat;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.DebugTextViewHelper;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import ie.macinnes.htsp.SimpleHtspConnection;
import ie.macinnes.tvheadend.Constants;
import ie.macinnes.tvheadend.R;
import ie.macinnes.tvheadend.TvContractUtils;

public class TvheadendPlayer implements Player.EventListener {

    private static final String TAG = TvheadendPlayer.class.getName();

    private static final float CAPTION_LINE_HEIGHT_RATIO = 0.0533f;
    private static final int TEXT_UNIT_PIXELS = 0;
    private static final long INVALID_TIMESHIFT_TIME = HtspDataSource.INVALID_TIMESHIFT_TIME;

    public interface Listener {
        /**
         * Called when ther player state changes.
         *
         * @param playWhenReady Whether playback will proceed when ready.
         * @param playbackState One of the {@code STATE} constants defined in the {@link ExoPlayer}
         *                      interface.
         */
        void onPlayerStateChanged(boolean playWhenReady, int playbackState);

        /**
         * Called when an error occurs.
         *
         * @param error The error.
         */
        void onPlayerError(ExoPlaybackException error);

        /**
         * Called when the available or selected tracks change.
         *
         * @param tracks a list of tracks
         */
        void onTracksChanged(List<TvTrackInfo> tracks, SparseArray<String> selectedTracks);
    }

    private final Context mContext;
    private final SimpleHtspConnection mConnection;
    private final Listener mListener;

    private final Handler mHandler;

    private SimpleExoPlayer mExoPlayer;
    private RenderersFactory mRenderersFactory;
    private TvheadendTrackSelector mTrackSelector;
    private LoadControl mLoadControl;
    private EventLogger mEventLogger;
    private HtspDataSource.Factory mHtspSubscriptionDataSourceFactory;
    private HtspDataSource.Factory mHtspFileInputStreamDataSourceFactory;
    private HtspDataSource mDataSource;
    private ExtractorsFactory mExtractorsFactory;

    private View mOverlayView;
    private DebugTextViewHelper mDebugViewHelper;
    private SubtitleView mSubtitleView;
    private LinearLayout mRadioInfoView;

    private MediaSource mMediaSource;

    private Uri mCurrentChannelUri;

    public TvheadendPlayer(Context context, SimpleHtspConnection connection, Listener listener) {
        mContext = context;
        mConnection = connection;
        mListener = listener;

        mHandler = new Handler();

        buildExoPlayer();
    }

    public void open(Uri channelUri) {
        // Stop any existing playback
        stop();

        mCurrentChannelUri = channelUri;

        // Create the media source
        if (channelUri.getHost().equals("channel")) {
            buildHtspChannelMediaSource(channelUri);
        } else {
            buildHtspRecordingMediaSource(channelUri);
        }

        // Prepare the media source
        mExoPlayer.prepare(mMediaSource);
    }

    public void release() {
        // Stop any existing playback
        stop();

        if (mDebugViewHelper != null) {
            mDebugViewHelper.stop();
            mDebugViewHelper = null;
        }

        mSubtitleView = null;
        mOverlayView = null;

        // Release ExoPlayer
        mExoPlayer.removeListener(this);
        mExoPlayer.release();
    }

    public void setSurface(Surface surface) {
        mExoPlayer.setVideoSurface(surface);
    }

    public void setVolume(float volume) {
        mExoPlayer.setVolume(volume);
    }

    public boolean selectTrack(int type, String trackId) {
        return mTrackSelector.selectTrack(type, trackId);
    }

    public void setCaptionEnabled(boolean enabled) {
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                .setRendererDisabled(C.TRACK_TYPE_TEXT, !enabled));
    }

    public void play() {
        // Start playback when ready
        mExoPlayer.setPlayWhenReady(true);
    }

    public void resume() {
        mExoPlayer.setPlayWhenReady(true);

        if (mDataSource != null) {
            Log.d(TAG, "Resuming HtspDataSource");
            mDataSource.resume();
        } else {
            Log.w(TAG, "Unable to resume, no HtspDataSource available");
        }
    }

    public void pause() {
        mExoPlayer.setPlayWhenReady(false);

        if (mDataSource != null) {
            Log.d(TAG, "Pausing HtspDataSource");
            mDataSource.pause();
        } else {
            Log.w(TAG, "Unable to pause, no HtspDataSource available");
        }
    }

    public void seek(long timeMs) {
        if (mDataSource != null) {
            Log.d(TAG, "Seeking to time: " + timeMs);

            long seekPts = (timeMs * 1000) - mDataSource.getTimeshiftStartTime();
            seekPts = Math.max(seekPts, mDataSource.getTimeshiftStartPts()) / 1000;
            Log.d(TAG, "Seeking to PTS: " + seekPts);

            mExoPlayer.seekTo(seekPts);
        } else {
            Log.w(TAG, "Unable to seek, no HtspDataSource available");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    public void setPlaybackParams(PlaybackParams params) {
        float rawSpeed = params.getSpeed();
        int speed = (int) rawSpeed;
        int translatedSpeed;

        switch (speed) {
            case 0:
                translatedSpeed = 100;
                break;
            case -2:
                translatedSpeed = -200;
                break;
            case -4:
                translatedSpeed = -300;
                break;
            case -12:
                translatedSpeed = -400;
                break;
            case -48:
                translatedSpeed = -500;
                break;
            case 2:
                translatedSpeed = 200;
                break;
            case 4:
                translatedSpeed = 300;
                break;
            case 12:
                translatedSpeed = 400;
                break;
            case 48:
                translatedSpeed = 500;
                break;
            default:
                Log.d(TAG, "Unknown speed??? " + rawSpeed);
                return;
        }

        Log.d(TAG, "Speed: " + params.getSpeed() + " / " + translatedSpeed);

        if (mDataSource != null) {
            mDataSource.setSpeed(translatedSpeed);
            mExoPlayer.setPlaybackParameters(new PlaybackParameters(translatedSpeed, 0));
        }
    }

    private void stop() {
        mExoPlayer.stop();
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters().clearSelectionOverrides());
        mHtspSubscriptionDataSourceFactory.releaseCurrentDataSource();
        mHtspFileInputStreamDataSourceFactory.releaseCurrentDataSource();

        if (mMediaSource != null) {
            // FIXME needed?
            //mMediaSource.releaseSource();
        }
    }

    public long getTimeshiftStartPosition() {
        if (mDataSource != null) {
            long startTime = mDataSource.getTimeshiftStartTime();
            if (startTime != INVALID_TIMESHIFT_TIME) {
                // For live content
                return startTime / 1000;
            } else {
                // For recorded content
                return 0;
            }
        } else {
            Log.w(TAG, "Unable to getTimeshiftStartPosition, no HtspDataSource available");
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public long getTimeshiftCurrentPosition() {
        if (mDataSource != null) {
            long offset = mDataSource.getTimeshiftOffsetPts();
            if (offset != INVALID_TIMESHIFT_TIME) {
                // For live content
                return System.currentTimeMillis() + (offset / 1000);
            } else {
                // For recorded content
                mExoPlayer.getCurrentPosition();
            }
        } else {
            Log.w(TAG, "Unable to getTimeshiftCurrentPosition, no HtspDataSource available");
        }

        return INVALID_TIMESHIFT_TIME;
    }

    public View getOverlayView(CaptioningManager.CaptionStyle captionStyle, float fontScale) {
        if (mOverlayView == null) {
            LayoutInflater lI = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mOverlayView = lI.inflate(R.layout.player_overlay_view, null);
        }

        if (mDebugViewHelper == null) {
            mDebugViewHelper = getDebugTextView();

            if (mDebugViewHelper != null) {
                mDebugViewHelper.start();
            }
        }

        if (mSubtitleView == null) {
            mSubtitleView = getSubtitleView(captionStyle, fontScale);

            if (mSubtitleView != null) {
                mExoPlayer.addTextOutput(mSubtitleView);
            }
        }

        if (mRadioInfoView == null) {
            mRadioInfoView = mOverlayView.findViewById(R.id.radio_info_view);
        }

        return mOverlayView;
    }

    private DebugTextViewHelper getDebugTextView() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        final boolean enableDebugTextView = sharedPreferences.getBoolean(
                Constants.KEY_DEBUG_TEXT_VIEW_ENABLED,
                mContext.getResources().getBoolean(R.bool.pref_default_debug_text_view_enabled)
        );

        if (enableDebugTextView) {
            TextView textView = mOverlayView.findViewById(R.id.debug_text_view);
            textView.setVisibility(View.VISIBLE);
            return new DebugTextViewHelper(
                    mExoPlayer, textView);
        } else {
            return null;
        }
    }

    private SubtitleView getSubtitleView(CaptioningManager.CaptionStyle captionStyle, float fontScale) {
        SubtitleView view = mOverlayView.findViewById(R.id.subtitle_view);

        CaptionStyleCompat captionStyleCompat = CaptionStyleCompat.createFromCaptionStyle(captionStyle);

        float captionTextSize = getCaptionFontSize();
        captionTextSize *= fontScale;

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        final boolean applyEmbeddedStyles = sharedPreferences.getBoolean(
                Constants.KEY_CAPTIONS_APPLY_EMBEDDED_STYLES,
                mContext.getResources().getBoolean(R.bool.pref_default_captions_apply_embedded_styles)
        );

        view.setStyle(captionStyleCompat);
        view.setVisibility(View.VISIBLE);
        view.setFixedTextSize(TEXT_UNIT_PIXELS, captionTextSize);
        view.setApplyEmbeddedStyles(applyEmbeddedStyles);

        return view;
    }

    // Misc Internal Methods
    private void buildExoPlayer() {
        mRenderersFactory = new TvheadendRenderersFactory(mContext);
        mTrackSelector = buildTrackSelector();
        mLoadControl = buildLoadControl();

        mExoPlayer = new SimpleExoPlayer.Builder(mContext, mRenderersFactory)
                .setLoadControl(mLoadControl)
                .setTrackSelector(mTrackSelector)
                .build();

        mExoPlayer.addListener(this);

        // Add the EventLogger
        mEventLogger = new EventLogger(mTrackSelector);
        mExoPlayer.addListener(mEventLogger);
        mExoPlayer.addAnalyticsListener(mEventLogger);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        final String streamProfile = sharedPreferences.getString(Constants.KEY_HTSP_STREAM_PROFILE, mContext.getResources().getString(R.string.pref_default_htsp_stream_profile));

        // Produces DataSource instances through which media data is loaded.
        mHtspSubscriptionDataSourceFactory = new HtspSubscriptionDataSource.Factory(mContext, mConnection, streamProfile);
        mHtspFileInputStreamDataSourceFactory = new HtspFileInputStreamDataSource.Factory(mContext, mConnection);

        // Produces Extractor instances for parsing the media data.
        mExtractorsFactory = new TvheadendExtractorsFactory(mContext);
    }

    private TvheadendTrackSelector buildTrackSelector() {
        TvheadendTrackSelector trackSelector = new TvheadendTrackSelector(mContext);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        final boolean enableAudioTunneling = sharedPreferences.getBoolean(
                Constants.KEY_AUDIO_TUNNELING_ENABLED,
                mContext.getResources().getBoolean(R.bool.pref_default_audio_tunneling_enabled)
        );

        if (enableAudioTunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(mContext)));
        }

        return trackSelector;
    }

    private LoadControl buildLoadControl() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        int bufferForPlaybackMs = Integer.parseInt(
                sharedPreferences.getString(
                        Constants.KEY_BUFFER_PLAYBACK_MS,
                        mContext.getResources().getString(R.string.pref_default_buffer_playback_ms)
                )
        );
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        bufferForPlaybackMs,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .createDefaultLoadControl();
    }

    private void buildHtspChannelMediaSource(Uri channelUri) {
        // This is the MediaSource representing the media to be played.
        mMediaSource = new ProgressiveMediaSource.Factory(mHtspSubscriptionDataSourceFactory, mExtractorsFactory)
                .createMediaSource(channelUri);

        mMediaSource.addEventListener(mHandler, mEventLogger);
    }

    private void buildHtspRecordingMediaSource(Uri recordingUri) {
        // This is the MediaSource representing the media to be played.
        mMediaSource = new ProgressiveMediaSource.Factory(mHtspFileInputStreamDataSourceFactory, mExtractorsFactory)
                .createMediaSource(recordingUri);

        mMediaSource.addEventListener(mHandler, mEventLogger);
    }

    private float getCaptionFontSize() {
        Display display = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        Point displaySize = new Point();
        display.getSize(displaySize);
        return Math.max(mContext.getResources().getDimension(R.dimen.subtitle_minimum_font_size),
                CAPTION_LINE_HEIGHT_RATIO * Math.min(displaySize.x, displaySize.y));
    }

    private boolean getTrackStatusBoolean(TrackSelection selection, TrackGroup group,
                                          int trackIndex) {
        return selection != null && selection.getTrackGroup() == group
                && selection.indexOf(trackIndex) != C.INDEX_UNSET;
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo = mTrackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo == null) {
            return;
        }

        // Process Tracks
        List<TvTrackInfo> tracks = new ArrayList<>();
        SparseArray<String> selectedTracks = new SparseArray<>();

        // Keep track of weather we have a video track available
        boolean hasVideoTrack = false;

        for (int rendererIndex = 0; rendererIndex < mappedTrackInfo.length; rendererIndex++) {
            TrackGroupArray rendererTrackGroups = mappedTrackInfo.getTrackGroups(rendererIndex);
            TrackSelection trackSelection = trackSelections.get(rendererIndex);
            if (rendererTrackGroups.length > 0) {
                for (int groupIndex = 0; groupIndex < rendererTrackGroups.length; groupIndex++) {
                    TrackGroup trackGroup = rendererTrackGroups.get(groupIndex);
                    for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                        int formatSupport = mappedTrackInfo.getTrackSupport(rendererIndex, groupIndex, trackIndex);

                        if (formatSupport == RendererCapabilities.FORMAT_HANDLED) {
                            Format format = trackGroup.getFormat(trackIndex);
                            TvTrackInfo tvTrackInfo = ExoPlayerUtils.buildTvTrackInfo(format);

                            if (tvTrackInfo != null) {
                                tracks.add(tvTrackInfo);

                                Boolean selected = getTrackStatusBoolean(trackSelection, trackGroup, trackIndex);

                                if (selected) {
                                    int trackType = MimeTypes.getTrackType(format.sampleMimeType);

                                    switch (trackType) {
                                        case C.TRACK_TYPE_VIDEO:
                                            hasVideoTrack = true;
                                            selectedTracks.put(TvTrackInfo.TYPE_VIDEO, format.id);
                                            break;
                                        case C.TRACK_TYPE_AUDIO:
                                            selectedTracks.put(TvTrackInfo.TYPE_AUDIO, format.id);
                                            break;
                                        case C.TRACK_TYPE_TEXT:
                                            selectedTracks.put(TvTrackInfo.TYPE_SUBTITLE, format.id);
                                            break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasVideoTrack) {
            disableRadioInfoScreen();
        } else {
            enableRadioInfoScreen();
        }

        mListener.onTracksChanged(tracks, selectedTracks);
    }

    private void enableRadioInfoScreen() {
        // No video track available, use the channel logo as a substitute
        Log.i(TAG, "No video track available");

        try {
            String channelName = TvContractUtils.getChannelName(mContext, Integer.parseInt(mCurrentChannelUri.getPath().substring(1)));
            TextView radioChannelName = mRadioInfoView.findViewById(R.id.radio_channel_name);
            radioChannelName.setText(channelName);

            ImageView radioChannelIcon = mRadioInfoView.findViewById(R.id.radio_channel_icon);

            long androidChannelId = TvContractUtils.getChannelId(mContext, Integer.parseInt(mCurrentChannelUri.getPath().substring(1)));
            Uri channelIconUri = TvContractCompat.buildChannelLogoUri(androidChannelId);

            InputStream is = mContext.getContentResolver().openInputStream(channelIconUri);

            BitmapDrawable iconImage = new BitmapDrawable(mContext.getResources(), is);
            radioChannelIcon.setImageDrawable(iconImage);

            mRadioInfoView.setVisibility(View.VISIBLE);
        } catch (IOException e) {
            Log.e(TAG, "Failed to fetch logo", e);
        }
    }

    private void disableRadioInfoScreen() {
        Log.d(TAG, "Video track is available");
        mRadioInfoView.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        if (isLoading) {
            // Fetch the current DataSource for later use
            // TODO: Hold a WeakReference to the DataSource instead...
            // TODO: We should know if we're playing a channel or a recording...
            mDataSource = mHtspSubscriptionDataSourceFactory.getCurrentDataSource();
            if (mDataSource == null) {
                mDataSource = mHtspFileInputStreamDataSourceFactory.getCurrentDataSource();
            }
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        mListener.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        mListener.onPlayerError(error);
    }
}
