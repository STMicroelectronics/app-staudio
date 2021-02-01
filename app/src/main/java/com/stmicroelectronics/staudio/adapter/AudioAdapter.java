package com.stmicroelectronics.staudio.adapter;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.stmicroelectronics.staudio.R;
import com.stmicroelectronics.staudio.data.AudioDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import timber.log.Timber;

/**
 * Audio adapter
 */

public class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.ViewHolder>{

    private final AudioAdapter.OnClickListener mListener;
    private final List<AudioDetails> arrayList = new ArrayList<>();

    public AudioAdapter(AudioAdapter.OnClickListener listener){
        mListener = listener;
    }
    private boolean mPlay = false;
    private int mPlayPosition = 0;

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.item_audio, parent,false);
        return new AudioAdapter.ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AudioDetails audio = arrayList.get(position);
        if (audio.getAudioArtist() == null || audio.getAudioArtist().isEmpty()) {
            holder.audioTitle.setText(audio.getAudioName());
        } else {
            String audioTitle = audio.getAudioName() + " (" + audio.getAudioArtist() + ")";
            holder.audioTitle.setText(audioTitle);
        }

        if (audio.isVolumeExternal()) {
            holder.volumeType.setText(R.string.volume_external);
        } else if (audio.isVolumePrimary()) {
            holder.volumeType.setText(R.string.volume_primary);
        } else {
            holder.volumeType.setText(R.string.volume_internal);
        }

        holder.togglePlay = true;
        holder.audioPlay.setImageResource(R.drawable.ic_play_arrow_white);

        if (audio.isRecordedFile()) {
            holder.audioDelete.setVisibility(View.VISIBLE);
        } else {
            holder.audioDelete.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return arrayList.size();
    }

    public boolean isPermissionGranted (List<UriPermission> permissionList) {
        for (AudioDetails details : arrayList) {
            details.setPermissionGranted(false);
            for (UriPermission permission:permissionList) {
                if ((permission.getUri() == null) || (permission.getUri() == details.getAudioUriPermission())) {
                    details.setPermissionGranted(true);
                    break;
                }
            }
            if (! details.isPermissionGranted()) {
                return false;
            }
        }
        return true;
    }

    public void resetPlayer() {
        mPlay = false;
    }

    public void playerCompletion(RecyclerView view){
        if (mPlay) {
            ViewHolder holder = (ViewHolder) view.findViewHolderForAdapterPosition(mPlayPosition);
            if (holder != null) {
                holder.audioPlay.setImageResource(R.drawable.ic_play_arrow_white);
                holder.togglePlay = true;
            }
            mPlay = false;
        }
    }

    public void addItem(AudioDetails audio) {
        int prevSize = arrayList.size();
        arrayList.add(audio);
        notifyItemRangeInserted(prevSize,1);
    }

    public void addItems(ArrayList<AudioDetails> list) {
        int prevSize = arrayList.size();
        arrayList.addAll(list);
        notifyItemRangeInserted(prevSize,arrayList.size() - prevSize);
    }

    public void removeAllItems() {
        int prevSize = arrayList.size();
        arrayList.clear();
        notifyItemRangeRemoved(0, prevSize);
    }

    public void removeItem(Uri uri) {
        int position = 0;
        for (AudioDetails item:arrayList) {
            if (item.getAudioUri().equals(uri)) {
                arrayList.remove(position);
                notifyItemRemoved(position);
                return;
            }
            position++;
        }
    }

    public void removeAllPrimaryItems() {
        int position = 0;
        int primarySize = arrayList.size();

        // found position of first item with primary
        for (AudioDetails item:arrayList) {
            if (item.isVolumePrimary()) {
                break;
            }
            position++;
        }

        // create remove condition (case volume primary)
        // do not remote recorded files from primary (can be removed manually)
        Predicate<AudioDetails> condition = new Predicate<AudioDetails>() {
            @Override
            public boolean test(AudioDetails audio) {
                return audio.isVolumePrimary() && ! audio.isRecordedFile();
            }
        };

        if (arrayList.removeIf(condition)) {
            primarySize -= arrayList.size();
            Timber.d("Remove External items:%d (%d)", position, primarySize);
            if (primarySize > 0) {
                notifyItemRangeRemoved(position, primarySize);
            }
        }
    }

    public void removeAllExternalItems() {
        int position = 0;
        int externalSize = arrayList.size();

        // found position of first item with external
        for (AudioDetails item:arrayList) {
            if (item.isVolumeExternal()) {
                break;
            }
            position++;
        }

        // create remove condition (case volume external)
        Predicate<AudioDetails> condition = new Predicate<AudioDetails>() {
            @Override
            public boolean test(AudioDetails audio) {
                return audio.isVolumeExternal();
            }
        };

        if (arrayList.removeIf(condition)) {
            externalSize -= arrayList.size();
            Timber.d("Remove External items:%d (%d)", position, externalSize);
            if (externalSize > 0) {
                notifyItemRangeRemoved(position, externalSize);
            }
        }
    }

    public interface OnClickListener {
        void onClick(AudioDetails audio, boolean togglePlay);
        void onDelete(AudioDetails audio);
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        TextView volumeType;
        TextView audioTitle;
        ImageButton audioPlay;
        ImageButton audioDelete;

        boolean togglePlay = true;

        ViewHolder(View itemView) {
            super(itemView);

            volumeType = itemView.findViewById(R.id.volume_type);
            audioTitle = itemView.findViewById(R.id.audio_title);
            audioPlay = itemView.findViewById(R.id.button_audio_play);
            audioDelete = itemView.findViewById(R.id.button_audio_delete);

            audioPlay.setOnClickListener(this);

            audioDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int adapterPosition = getAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        AudioDetails audio = arrayList.get(adapterPosition);
                        mListener.onDelete(audio);
                    }
                }
            });
        }

        @Override
        public void onClick(View v) {
            int adapterPosition = getAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                AudioDetails audio = arrayList.get(adapterPosition);
                if (audio.getAudioUri() != null) {
                    if ((togglePlay) && (!mPlay)) {
                        audioPlay.setImageResource(R.drawable.ic_pause_white);
                        mPlayPosition = adapterPosition;
                        mPlay = true;
                        mListener.onClick(audio, togglePlay);
                        togglePlay = false;
                    } else if ((!togglePlay) && (mPlay)) {
                        audioPlay.setImageResource(R.drawable.ic_play_arrow_white);
                        mPlay = false;
                        mListener.onClick(audio, togglePlay);
                        togglePlay = true;
                    } else {
                        // just call onClick to display message
                        mListener.onClick(null, false);
                    }
                }
            } else {
                Timber.d("position is not yet available");
            }
        }
    }

}
