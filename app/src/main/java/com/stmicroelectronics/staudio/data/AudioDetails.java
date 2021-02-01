package com.stmicroelectronics.staudio.data;

import android.net.Uri;

/**
 * Audio Details data class
 */

public class AudioDetails {

    public final static int AUDIO_VOLUME_INTERNAL = 1;
    public final static int AUDIO_VOLUME_EXTERNAL = 2;
    public final static int AUDIO_VOLUME_EXTERNAL_PRIMARY = 3;

    private int mVolume;

    private String mAudioName;
    private String mAudioArtist;
    private Long mAudioDuration;

    private Uri mAudioUri;

    private Uri mAudioUriPermission;
    private boolean mPermissionGranted;

    public String getAudioName() {
        return mAudioName.replace("_staudio_","_");
    }
    public void setAudioName(String audioName) {
        mAudioName = audioName;
    }

    public String getAudioArtist() {
        return mAudioArtist;
    }
    public void setAudioArtist(String audioArtist) {
        mAudioArtist = audioArtist;
    }

    public Long getAudioDuration() {
        return mAudioDuration;
    }
    public void  setAudioDuration(Long duration) {
        mAudioDuration = duration;
    }

    public Uri getAudioUri() {
        return mAudioUri;
    }
    public void setAudioUri(Uri audioUri) {
        mAudioUri = audioUri;
    }

    public Uri getAudioUriPermission() {
        return mAudioUriPermission;
    }
    public void setAudioUriPermission(Uri audioUri) {
        mAudioUriPermission = audioUri;
    }

    public boolean isPermissionGranted(){return mPermissionGranted;}
    public void setPermissionGranted(boolean granted){ mPermissionGranted = granted;}

    public void setVolume(int volume) {
        mVolume = volume;
    }

    public boolean isRecordedFile() {
        return mAudioName.contains("staudio");
    }

    public boolean isVolumePrimary() {
        return mVolume == AUDIO_VOLUME_EXTERNAL_PRIMARY;
    }
    public boolean isVolumeInternal() {
        return mVolume == AUDIO_VOLUME_INTERNAL;
    }
    public boolean isVolumeExternal() {
        return mVolume == AUDIO_VOLUME_EXTERNAL;
    }
}
