
package com.github.rtoshiro.view.video;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import java.io.IOException;

/**
 * Acts like a {@link android.widget.VideoView} with fullscreen funcionality
 *
 * @author rtoshiro
 * @version 2015.0527
 * @since 1.7
 */
public class FullscreenVideoView extends RelativeLayout implements SurfaceHolder.Callback, MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnCompletionListener {

    /**
     * Debug Tag for use logging debug output to LogCat
     */
    private final static String TAG = "FullscreenVideoView";

    protected Context context;
    protected Activity activity;

    protected MediaPlayer mediaPlayer;
    protected SurfaceHolder surfaceHolder;
    protected SurfaceView surfaceView;
    protected boolean videoIsReady, surfaceIsReady;
    protected boolean detachedByFullscreen;
    protected State currentState;
    protected State lastState; // Tells onSeekCompletion what to do

    protected View loadingView;

    // Controla o fullscreen
    protected ViewGroup parentView;
    protected boolean isFullscreen;
    protected int initialConfigOrientation;
    protected int initialMovieWidth, initialMovieHeight;

    protected MediaPlayer.OnErrorListener errorListener;
    protected MediaPlayer.OnPreparedListener preparedListener;
    protected MediaPlayer.OnSeekCompleteListener seekCompleteListener;
    protected MediaPlayer.OnCompletionListener completionListener;

    /**
     States of MediaPlayer
     http://developer.android.com/reference/android/media/MediaPlayer.html
     */
    public enum State
    {
        IDLE,
        INITIALIZED,
        PREPARED,
        PREPARING,
        STARTED,
        STOPPED,
        PAUSED,
        PLAYBACKCOMPLETED,
        ERROR,
        END
    }

    public FullscreenVideoView(Context context) {
        super(context);
        this.context = context;

        init();
    }

    public FullscreenVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        init();
    }

    public FullscreenVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.context = context;

        init();
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow - detachedByFullscreen: " + detachedByFullscreen);

        super.onDetachedFromWindow();

        if (!detachedByFullscreen) {
            if (mediaPlayer != null) {
                mediaPlayer.setOnPreparedListener(null);

                if (mediaPlayer.isPlaying())
                    mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            videoIsReady = false;
            surfaceIsReady = false;
            currentState = State.END;
        }

        detachedByFullscreen = false;
    }

    @Override
    synchronized public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated called = " + currentState);

        mediaPlayer.setDisplay(surfaceHolder);

        // If is not prepared yet - tryToPrepare()
        if (!surfaceIsReady)
        {
            surfaceIsReady = true;
            if (currentState != State.PREPARED &&
                    currentState != State.PAUSED &&
                    currentState != State.STARTED &&
                    currentState != State.PLAYBACKCOMPLETED)
                tryToPrepare();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged called");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed called");
        if (mediaPlayer != null && mediaPlayer.isPlaying())
            mediaPlayer.pause();

        surfaceIsReady = false;
    }

    @Override
    synchronized public void onPrepared(MediaPlayer mp) {
        Log.d(TAG, "onPrepared called");
        videoIsReady = true;
        tryToPrepare();

        if (this.preparedListener != null)
            this.preparedListener.onPrepared(mp);
    }

    /**
     * Restore the last State before seekTo()
     *
     * @param mp the MediaPlayer that issued the seek operation
     */
    @Override
    public void onSeekComplete(MediaPlayer mp) {
        Log.d(TAG, "onSeekComplete");

        stopLoading();
        switch (lastState)
        {
            case STARTED:
            {
                start();
                break;
            }
            case PAUSED:
            {
                pause();
                break;
            }
            case PLAYBACKCOMPLETED:
            {
                currentState = State.PLAYBACKCOMPLETED;
                break;
            }
            case PREPARED:
            {
                currentState = State.PREPARED;
                break;
            }
        }

        if (this.seekCompleteListener != null)
            this.seekCompleteListener.onSeekComplete(mp);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (!this.mediaPlayer.isLooping())
            this.currentState = State.PLAYBACKCOMPLETED;
        else
            this.currentState = State.STARTED;

        if (this.completionListener != null)
            this.completionListener.onCompletion(mp);
    }


    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.d(TAG, "onError called");

        stopLoading();
        this.currentState = State.ERROR;

        if (this.errorListener != null)
            return this.errorListener.onError(mp, what, extra);
        return false;
    }

    /**
     * Initializes the UI
     */
    protected void init() {
        this.currentState = State.IDLE;
        this.isFullscreen = false;
        this.initialConfigOrientation = -1;
        this.setBackgroundColor(Color.BLACK);

        this.mediaPlayer = new MediaPlayer();

        this.surfaceView = new SurfaceView(context);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        layoutParams.addRule(CENTER_IN_PARENT);
        this.surfaceView.setLayoutParams(layoutParams);
        addView(this.surfaceView);

        this.surfaceHolder = this.surfaceView.getHolder();
        //noinspection deprecation
        this.surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        this.surfaceHolder.addCallback(this);

        this.loadingView = new ProgressBar(context);
        layoutParams = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(CENTER_IN_PARENT);
        this.loadingView.setLayoutParams(layoutParams);
        addView(this.loadingView);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            this.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    Log.i(TAG, "onLayoutChange");

                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            resize();
                        }
                    });
                }
            });
        }
    }

    /**
     * Calls prepare() method of MediaPlayer
     */
    protected void prepare() throws IllegalStateException {
        startLoading();

        this.videoIsReady = false;
        this.initialMovieHeight = -1;
        this.initialMovieWidth = -1;

        this.mediaPlayer.setOnPreparedListener(this);
        this.mediaPlayer.setOnErrorListener(this);
        this.mediaPlayer.setOnSeekCompleteListener(this);
        this.mediaPlayer.setOnCompletionListener(this);
        this.mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        this.currentState = State.PREPARING;
        this.mediaPlayer.prepareAsync();
    }

    /**
     * Try to call state PREPARED
     * Only if SurfaceView is already created and MediaPlayer is prepared
     * Video is loaded and is ok to play.
     */
    protected void tryToPrepare() {
        if (this.surfaceIsReady && this.videoIsReady) {
            if (this.mediaPlayer != null)
            {
                this.initialMovieWidth = this.mediaPlayer.getVideoWidth();
                this.initialMovieHeight = this.mediaPlayer.getVideoHeight();
            }

            resize();
            stopLoading();
            currentState = State.PREPARED;
        }
    }

    protected void startLoading() {
        this.loadingView.setVisibility(View.VISIBLE);
    }

    protected void stopLoading() {
        this.loadingView.setVisibility(View.GONE);
    }

    /**
     * Get the current {@link FullscreenVideoView.State}.
     * @return
     */
    synchronized public State getCurrentState() {
        return currentState;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
        this.initialConfigOrientation = activity.getRequestedOrientation();
    }

    public void resize() {
        if (initialMovieHeight == -1 || initialMovieWidth == -1)
            return;

        View currentParent = (View) getParent();

        float videoProportion = (float) initialMovieWidth / (float) initialMovieHeight;

        int screenWidth = currentParent.getWidth();
        int screenHeight = currentParent.getHeight();
        float screenProportion = (float) screenWidth / (float) screenHeight;

        int newWidth, newHeight;
        if (videoProportion > screenProportion) {
            newWidth = screenWidth;
            newHeight = (int) ((float) screenWidth / videoProportion);
        } else {
            newWidth = (int) (videoProportion * (float) screenHeight);
            newHeight = screenHeight;
        }

        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        if (lp.width != newWidth || lp.height != newHeight) {
            lp.width = newWidth;
            lp.height = newHeight;
            surfaceView.setLayoutParams(lp);
        }
    }

    /**
     * Toggles view to fullscreen mode
     * It saves currentState and calls pause() method.
     * When fullscreen is finished, it calls the saved currentState before pause()
     * In practice, it only affects STARTED state.
     * If currenteState was STARTED when fullscreen() is called, it calls start() method
     * after fullscreen() has ended.
     */
    public void fullscreen() throws IllegalStateException {
        if (mediaPlayer == null) throw new RuntimeException("Media Player is not initialized");

        detachedByFullscreen = true;

        boolean wasPlaying = mediaPlayer.isPlaying();
        if (wasPlaying)
            pause();

        if (!isFullscreen) {
            isFullscreen = true;

            if (activity != null)
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

            View v = getRootView();
            ViewParent viewParent = getParent();
            if (viewParent instanceof ViewGroup) {
                if (parentView == null)
                    parentView = (ViewGroup) viewParent;

                Log.d(TAG, "removeView");
                parentView.removeView(this);
            } else
                Log.e(TAG, "Parent View is not a ViewGroup");

            if (v instanceof ViewGroup) {
                Log.d(TAG, "addView");
                ((ViewGroup) v).addView(this);
            }
            else
                Log.e(TAG, "RootView is not a ViewGroup");
        } else {
            isFullscreen = false;

            if (activity != null)
                activity.setRequestedOrientation(initialConfigOrientation);

            ViewParent viewParent = getParent();
            if (viewParent instanceof ViewGroup) {
                Log.d(TAG, "removeView");
                ((ViewGroup) viewParent).removeView(this);
                Log.d(TAG, "addView");
                parentView.addView(this);
            }
        }

        resize();

        if (wasPlaying && mediaPlayer != null)
            start();
    }

    /**
     * {@link MediaPlayer} method (getCurrentPosition)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#getCurrentPosition%28%29
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null)
            return mediaPlayer.getCurrentPosition();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (getDuration)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#getDuration%28%29
     */
    public int getDuration() {
        if (mediaPlayer != null)
            return mediaPlayer.getDuration();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (getVideoHeight)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#getVideoHeight%28%29
     */
    public int getVideoHeight() {
        if (mediaPlayer != null)
            return mediaPlayer.getVideoHeight();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (getVideoWidth)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#getVideoWidth%28%29
     */
    public int getVideoWidth() {
        if (mediaPlayer != null)
            return mediaPlayer.getVideoWidth();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (isLooping)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#isLooping%28%29
     */
    public boolean isLooping() {
        if (mediaPlayer != null)
            return mediaPlayer.isLooping();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (isPlaying)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#isLooping%28%29
     */
    public boolean isPlaying() throws IllegalStateException {
        if (mediaPlayer != null)
            return mediaPlayer.isPlaying();
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (pause)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#pause%28%29
     */
    public void pause() throws IllegalStateException {
        if (mediaPlayer != null) {
            currentState = State.PAUSED;
            mediaPlayer.pause();
        }
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (reset)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#reset%28%29
     */
    public void reset() {
        if (mediaPlayer != null) {
            currentState = State.IDLE;
            mediaPlayer.reset();
        }
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (start)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#start%28%29
     */
    public void start() throws IllegalStateException {
        if (mediaPlayer != null) {
            currentState = State.STARTED;
            mediaPlayer.start();
        }
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (stop)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#stop%28%29
     */
    public void stop() throws IllegalStateException {
        if (mediaPlayer != null) {
            currentState = State.STOPPED;
            mediaPlayer.stop();
        }
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * {@link MediaPlayer} method (seekTo)
     * http://developer.android.com/reference/android/media/MediaPlayer.html#stop%28%29
     *
     * It calls pause() method before calling MediaPlayer.seekTo()
     *
     * @param msec the offset in milliseconds from the start to seek to
     * @throws IllegalStateException if the internal player engine has not been initialized
     */
    public void seekTo(int msec) throws IllegalStateException{
        if (mediaPlayer != null) {
            // No live streaming
            if (mediaPlayer.getDuration() > -1 && msec <= mediaPlayer.getDuration())
            {
                lastState = currentState;
                pause();
                mediaPlayer.seekTo(msec);

                startLoading();
            }
        }
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) {
        if (mediaPlayer != null)
            this.completionListener = l;
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener l) {
        if (mediaPlayer != null)
            errorListener = l;
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener l) {
        if (mediaPlayer != null)
            mediaPlayer.setOnBufferingUpdateListener(l);
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnInfoListener(MediaPlayer.OnInfoListener l) {
        if (mediaPlayer != null)
            mediaPlayer.setOnInfoListener(l);
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnSeekCompleteListener(MediaPlayer.OnSeekCompleteListener l) {
        if (mediaPlayer != null)
            this.seekCompleteListener = l;
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnVideoSizeChangedListener(MediaPlayer.OnVideoSizeChangedListener l) {
        if (mediaPlayer != null)
            mediaPlayer.setOnVideoSizeChangedListener(l);
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
        if (mediaPlayer != null)
            this.preparedListener = l;
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setLooping(boolean looping) {
        if (mediaPlayer != null)
            mediaPlayer.setLooping(looping);
        else throw new RuntimeException("Media Player is not initialized");
    }

    public void setVolume(float leftVolume, float rightVolume) {
        if (mediaPlayer != null)
            mediaPlayer.setVolume(leftVolume, rightVolume);
        else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * VideoView method (setVideoPath)
     */
    public void setVideoPath(String path) throws IOException, IllegalStateException, SecurityException, IllegalArgumentException, RuntimeException {
        if (mediaPlayer != null) {
            if (currentState != State.IDLE)
                throw new IllegalStateException("FullscreenVideoView Invalid State: " + currentState);

            mediaPlayer.setDataSource(path);

            currentState = State.INITIALIZED;
            prepare();
        } else throw new RuntimeException("Media Player is not initialized");
    }

    /**
     * VideoView method (setVideoURI)
     */
    public void setVideoURI(Uri uri) throws IOException, IllegalStateException, SecurityException, IllegalArgumentException, RuntimeException {
        if (mediaPlayer != null) {
            if (currentState != State.IDLE)
                throw new IllegalStateException("FullscreenVideoView Invalid State: " + currentState);

            mediaPlayer.setDataSource(context, uri);

            currentState = State.INITIALIZED;
            prepare();
        } else throw new RuntimeException("Media Player is not initialized");
    }
}
