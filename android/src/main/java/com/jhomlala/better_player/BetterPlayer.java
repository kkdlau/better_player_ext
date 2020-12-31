package com.jhomlala.better_player;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ControlDispatcher;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.ui.PlayerNotificationManager;

import androidx.annotation.Nullable;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.android.exoplayer2.PlaybackParameters;

final class BetterPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";
    private static final String DEFAULT_NOTIFICATION_CHANNEL = "BETTER_PLAYER_NOTIFICATION";
    private static final int NOTIFICATION_ID = 20772077;

    private SimpleExoPlayer exoPlayer;

    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private QueuingEventSink eventSink = new QueuingEventSink();

    private final EventChannel eventChannel;

    private boolean isInitialized = false;

    private String key;

    private DefaultTrackSelector trackSelector;

    private long maxCacheSize;
    private long maxCacheFileSize;

    private PlayerNotificationManager playerNotificationManager;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private EventListener exoPlayerEventListener;
    private Bitmap bitmap;

    BetterPlayer(
            Context context,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            Result result) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;

        trackSelector = new DefaultTrackSelector();
        exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector);

        setupVideoPlayer(eventChannel, textureEntry, result);
    }

    void setDataSource(
            Context context, String key, String dataSource, String formatHint, Result result,
            Map<String, String> headers, boolean useCache, long maxCacheSize, long maxCacheFileSize) {
        this.key = key;

        isInitialized = false;

        Uri uri = Uri.parse(dataSource);
        DataSource.Factory dataSourceFactory;


        if (isHTTP(uri)) {
            DefaultHttpDataSourceFactory defaultHttpDataSourceFactory =
                    new DefaultHttpDataSourceFactory(
                            "ExoPlayer",
                            null,
                            DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                            DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                            true);
            if (headers != null) {
                defaultHttpDataSourceFactory.getDefaultRequestProperties().set(headers);
            }

            if (useCache && maxCacheSize > 0 && maxCacheFileSize > 0) {
                dataSourceFactory =
                        new CacheDataSourceFactory(context, maxCacheSize, maxCacheFileSize, defaultHttpDataSourceFactory);
            } else {
                dataSourceFactory = defaultHttpDataSourceFactory;
            }
        } else {
            dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
        }

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, context);
        exoPlayer.prepare(mediaSource);

        result.success(null);
    }

    public void setupPlayerNotification(Context context, String title, String author, String imageUrl, String notificationChannelName) {

        PlayerNotificationManager.MediaDescriptionAdapter mediaDescriptionAdapter
                = new PlayerNotificationManager.MediaDescriptionAdapter() {
            @Override
            public String getCurrentContentTitle(Player player) {
                return title;
            }

            @Nullable
            @Override
            public PendingIntent createCurrentContentIntent(Player player) {
                return null;
            }

            @Nullable
            @Override
            public String getCurrentContentText(Player player) {
                return author;
            }

            @Nullable
            @Override
            public Bitmap getCurrentLargeIcon(Player player, PlayerNotificationManager.BitmapCallback callback) {
                if (imageUrl == null){
                    return null;
                }
                if (bitmap != null){
                    return bitmap;
                }
                new Thread(() -> {
                    bitmap = null;
                    if (imageUrl.contains("http")){
                        bitmap = getBitmapFromExternalURL(imageUrl);
                    } else {
                        bitmap = getBitmapFromInternalURL(imageUrl);
                    }

                    Bitmap finalBitmap = bitmap;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        callback.onBitmap(finalBitmap);
                    });

                }).start();
                return null;
            }
        };

        String playerNotificationChannelName = notificationChannelName;
        if (notificationChannelName == null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                NotificationChannel channel = new NotificationChannel(DEFAULT_NOTIFICATION_CHANNEL,
                        DEFAULT_NOTIFICATION_CHANNEL, importance);
                channel.setDescription(DEFAULT_NOTIFICATION_CHANNEL);
                NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
                notificationManager.createNotificationChannel(channel);
                playerNotificationChannelName = DEFAULT_NOTIFICATION_CHANNEL;
            }
        }


        playerNotificationManager = new PlayerNotificationManager(context,
                playerNotificationChannelName,
                NOTIFICATION_ID,
                mediaDescriptionAdapter);
        playerNotificationManager.setPlayer(exoPlayer);
        playerNotificationManager.setUseNavigationActions(false);
        playerNotificationManager.setStopAction(null);

        MediaSessionCompat mediaSession = new MediaSessionCompat(context, "ExoPlayer");

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onSeekTo(long pos) {
                exoPlayer.seekTo(pos);
                Map<String, Object> event = new HashMap<>();
                event.put("event", "seek");
                event.put("position", pos);
                eventSink.success(event);
                super.onSeekTo(pos);
            }

            @Override
            public void onCommand(String command, Bundle extras, ResultReceiver cb) {
                super.onCommand(command, extras, cb);
            }
        });
        mediaSession.setActive(true);
        playerNotificationManager.setMediaSessionToken(mediaSession.getSessionToken());


        playerNotificationManager.setControlDispatcher(new ControlDispatcher() {
            @Override
            public boolean dispatchSetPlayWhenReady(Player player, boolean playWhenReady) {
                String eventType = "";
                if (player.getPlayWhenReady()) {
                    eventType = "pause";
                } else {
                    eventType = "play";
                }


                Map<String, Object> event = new HashMap<>();
                event.put("event", eventType);
                eventSink.success(event);

                return true;
            }

            @Override
            public boolean dispatchSeekTo(Player player, int windowIndex, long positionMs) {
                Map<String, Object> event = new HashMap<>();
                event.put("event", "seek");
                event.put("position", positionMs);
                eventSink.success(event);
                return true;
            }

            @Override
            public boolean dispatchSetRepeatMode(Player player, int repeatMode) {
                return false;
            }

            @Override
            public boolean dispatchSetShuffleModeEnabled(Player player, boolean shuffleModeEnabled) {
                return false;
            }

            @Override
            public boolean dispatchStop(Player player, boolean reset) {
                return false;
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            refreshHandler = new Handler();
            refreshRunnable = new Runnable() {
                @Override
                public void run() {
                    PlaybackStateCompat playbackState = null;
                    if (exoPlayer.getPlayWhenReady()) {
                        playbackState = new PlaybackStateCompat.Builder()
                                .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                                .setState(PlaybackStateCompat.STATE_PAUSED, getPosition(), 1.0f)
                                .build();
                    } else {
                        playbackState = new PlaybackStateCompat.Builder()
                                .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                                .setState(PlaybackStateCompat.STATE_PLAYING, getPosition(), 1.0f)
                                .build();
                    }

                    mediaSession.setPlaybackState(playbackState);
                    refreshHandler.postDelayed(refreshRunnable, 1000);
                }
            };
            refreshHandler.postDelayed(refreshRunnable, 0);
        }

        exoPlayerEventListener = new EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, exoPlayer.getDuration())
                        .build());
            }
        };

        exoPlayer.addListener(exoPlayerEventListener);
        exoPlayer.seekTo(0);
    }

    public void removeNotificationData(){
        exoPlayer.removeListener(exoPlayerEventListener);
        if (refreshHandler != null) {
            refreshHandler.removeCallbacksAndMessages(null);
            refreshHandler = null;
            refreshRunnable = null;
        }
        if (playerNotificationManager != null){
            playerNotificationManager.setPlayer(null);
        }
        bitmap = null;
    }

    private static Bitmap getBitmapFromInternalURL(String src){
        try {
            File file = new File(src);
            return BitmapFactory.decodeFile(src);
        } catch (Exception exception){
            return null;
        }
    }
    private static Bitmap getBitmapFromExternalURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);
            return myBitmap;
        } catch (IOException exception) {
            return null;
        }
    }


    private static boolean isHTTP(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return scheme.equals("http") || scheme.equals("https");
    }

    private MediaSource buildMediaSource(
            Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {
        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri.getLastPathSegment());
        } else {
            switch (formatHint) {
                case FORMAT_SS:
                    type = C.TYPE_SS;
                    break;
                case FORMAT_DASH:
                    type = C.TYPE_DASH;
                    break;
                case FORMAT_HLS:
                    type = C.TYPE_HLS;
                    break;
                case FORMAT_OTHER:
                    type = C.TYPE_OTHER;
                    break;
                default:
                    type = -1;
                    break;
            }
        }
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                        new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
                        .createMediaSource(uri);
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        new DefaultDataSourceFactory(context, null, mediaDataSourceFactory))
                        .createMediaSource(uri);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory).createMediaSource(uri);
            case C.TYPE_OTHER:
                return new ExtractorMediaSource.Factory(mediaDataSourceFactory)
                        .setExtractorsFactory(new DefaultExtractorsFactory())
                        .createMediaSource(uri);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void setupVideoPlayer(
            EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry, Result result) {

        eventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink sink) {
                        eventSink.setDelegate(sink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        eventSink.setDelegate(null);
                    }
                });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer);

        exoPlayer.addListener(
                new EventListener() {

                    @Override
                    public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            sendBufferingUpdate();
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "bufferingStart");
                            eventSink.success(event);
                        } else if (playbackState == Player.STATE_READY) {
                            if (isInitialized) {
                                Map<String, Object> event = new HashMap<>();
                                event.put("event", "bufferingEnd");
                                eventSink.success(event);
                            } else {
                                isInitialized = true;
                                sendInitialized();
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "completed");
                            event.put("key", key);
                            eventSink.success(event);
                        }
                    }

                    @Override
                    public void onPlayerError(final ExoPlaybackException error) {
                        if (eventSink != null) {
                            eventSink.error("VideoError", "Video player had error " + error, null);
                        }
                    }
                });

        Map<String, Object> reply = new HashMap<>();
        reply.put("textureId", textureEntry.id());
        result.success(reply);
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    @SuppressWarnings("deprecation")
    private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            exoPlayer.setAudioAttributes(
                    new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build());
        } else {
            exoPlayer.setAudioStreamType(C.STREAM_TYPE_MUSIC);
        }
    }

    void play() {
        exoPlayer.setPlayWhenReady(true);
    }

    void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
        exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
        float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
        exoPlayer.setVolume(bracketedValue);
    }

    void setSpeed(double value) {
        float bracketedValue = (float) value;
        PlaybackParameters playbackParameters = new PlaybackParameters(bracketedValue);
        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    void setTrackParameters(int width, int height, int bitrate) {
        DefaultTrackSelector.ParametersBuilder parametersBuilder = trackSelector.buildUponParameters();
        if (width != 0 && height != 0) {
            parametersBuilder.setMaxVideoSize(width, height);
        }
        if (bitrate != 0) {
            parametersBuilder.setMaxVideoBitrate(bitrate);
        }
        trackSelector.setParameters(parametersBuilder);
    }

    void seekTo(int location) {
        exoPlayer.seekTo(location);
    }

    long getPosition() {
        return exoPlayer.getCurrentPosition();
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("key", key);
            event.put("duration", exoPlayer.getDuration());

            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer.getVideoFormat().height;
                    height = exoPlayer.getVideoFormat().width;
                }
                event.put("width", width);
                event.put("height", height);
            }
            eventSink.success(event);
        }
    }

    void dispose() {
        removeNotificationData();
        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BetterPlayer that = (BetterPlayer) o;

        if (exoPlayer != null ? !exoPlayer.equals(that.exoPlayer) : that.exoPlayer != null)
            return false;
        return surface != null ? surface.equals(that.surface) : that.surface == null;
    }

    @Override
    public int hashCode() {
        int result = exoPlayer != null ? exoPlayer.hashCode() : 0;
        result = 31 * result + (surface != null ? surface.hashCode() : 0);
        return result;
    }
}


