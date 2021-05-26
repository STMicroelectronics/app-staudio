package com.stmicroelectronics.staudio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.stmicroelectronics.staudio.adapter.AudioAdapter;
import com.stmicroelectronics.staudio.data.AudioDetails;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity implements AudioAdapter.OnClickListener {

    // Limit scan of external storage to root and Music directories
    private final static String EXTERNAL_MUSIC_DIR_NAME = "Music";
    private final static String INTERNAL_RECORD_DIR_NAME = "Record";

    private AudioAdapter mAudioAdapter;

    private MediaPlayer mPlayer;
    private Uri mCurrentAudioUri;

    RecyclerView mAudioListView;
    SwipeRefreshLayout mSwipeRefreshView;
    TextView mAudioMsgView;

    CardView mRecordTitleView;
    LinearLayout mRecordCardView;

    EditText mRecordEditTextView;
    ImageButton mRecordButtonView;

    ImageButton mScanUsbButton;

    Timer mTimer;

    private boolean mPrimaryStorageReadGranted = false;
    private boolean mPrimaryStorageWriteGranted = false;
    private boolean mRecordAudioGranted = false;

    private boolean mExternalAccessGranted = false;

    private MediaRecorder mMediaRecorder;
    private boolean mMediaRecorderStarted = false;
    private boolean mMediaRecorderState = false;
    private File mMediaRecorderFile;
    private boolean mMediaRecorderFilePrimary = false;

    private int mNbUSBDevices = 0;
    private boolean mNewUSBDeviceAttached = false;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            UsbDevice usbDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (this) {
                    for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                        if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                            mNbUSBDevices--;
                            if (mNbUSBDevices < 0) {
                                mNbUSBDevices = 0;
                            }
                            Timber.d("USB device (USB_CLASS_MASS_STORAGE) detached");
                        }
                    }
                }
                if (mNbUSBDevices == 0) {
                    mScanUsbButton.setVisibility(View.INVISIBLE);
                    mAudioAdapter.removeAllExternalItems();
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                synchronized (this) {
                    for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                        if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                            mNbUSBDevices++;
                            mNewUSBDeviceAttached = true;
                            Timber.d("USB device (USB_CLASS_MASS_STORAGE) attached");
                        }
                    }
                }
                if (mNbUSBDevices > 0) {
                    mScanUsbButton.setVisibility(View.VISIBLE);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAudioListView = findViewById(R.id.audio_list);
        mSwipeRefreshView = findViewById(R.id.swipe_refresh_audio);
        mAudioMsgView = findViewById(R.id.audio_msg);
        mRecordTitleView = findViewById(R.id.record_title);
        mRecordCardView = findViewById(R.id.record_card);
        mRecordEditTextView = findViewById(R.id.record_edit);
        mRecordButtonView = findViewById(R.id.record_button);
        mScanUsbButton = findViewById(R.id.button_usb);

        // initialize the RecyclerView for audio list
        initRecycler();

        // initialize the refresh listener
        mSwipeRefreshView.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                mSwipeRefreshView.setRefreshing(true);
                mAudioAdapter.removeAllItems();
                parseInternal();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    parsePrimary();
                }
                // just update from granted URI
                if (mExternalAccessGranted) {
                    List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
                    for (UriPermission permission : list) {
                        parseExternal(permission.getUri());
                    }
                }
                if (mAudioAdapter.getItemCount() > 0) {
                    mAudioMsgView.setVisibility(View.GONE);
                } else {
                    mAudioMsgView.setVisibility(View.VISIBLE);
                }
                mSwipeRefreshView.setRefreshing(false);
            }
        });
        mSwipeRefreshView.setColorSchemeResources(R.color.colorPrimary, R.color.colorPrimaryDark, R.color.colorAccent);

        // Register an intent filter so we can get device attached/removed messages
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);

        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        if (usbManager != null) {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            for (UsbDevice usbDevice : deviceList.values()) {
                for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                    if (usbDevice.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                        mNbUSBDevices++;
                    }
                }
            }
        }

        if (mNbUSBDevices > 0) {
            mScanUsbButton.setVisibility(View.VISIBLE);
        } else {
            mScanUsbButton.setVisibility(View.INVISIBLE);
        }

        // initialize media player
        initPlayer();

        // parse internal storage (no permission required)
        parseInternal();

        // check if there is already some URI permissions granted
        if (! mExternalAccessGranted) {
            List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
            if (! list.isEmpty()) {
                mExternalAccessGranted = true;
            }
        }

        // ask for external storage access
        checkPermission();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
        }
        super.onDestroy();
    }

    private void initRecycler() {
        mAudioAdapter = new AudioAdapter(this);
        mAudioListView.setAdapter(mAudioAdapter);
        DividerItemDecoration itemDecoration = new DividerItemDecoration(this,DividerItemDecoration.VERTICAL);
        Drawable itemDrawable = ContextCompat.getDrawable(this, R.drawable.itemdecoration);
        if (itemDrawable != null) {
            itemDecoration.setDrawable(itemDrawable);
        }
        mAudioListView.addItemDecoration(itemDecoration);
        mAudioListView.setLayoutManager(new LinearLayoutManager(this));
    }

    private final int REQUEST_PERMISSION_ST_AUDIO=1;

    private void checkPermission() {

        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                || (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)){
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSION_ST_AUDIO);
        } else {
            mPrimaryStorageReadGranted = true;
            Timber.d("Permission required (already) Granted!");
            mSwipeRefreshView.setRefreshing(true);
            mAudioAdapter.removeAllItems();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                parsePrimary();
            }
            if (mExternalAccessGranted) {
                mAudioAdapter.removeAllExternalItems();
                List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
                for (UriPermission permission : list) {
                    parseExternal(permission.getUri());
                }
            }
            if (mAudioAdapter.getItemCount() > 0) {
                mAudioMsgView.setVisibility(View.GONE);
            } else {
                mAudioMsgView.setVisibility(View.VISIBLE);
            }
            mSwipeRefreshView.setRefreshing(false);

            mRecordTitleView.setVisibility(View.VISIBLE);
            mRecordCardView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_ST_AUDIO) {
            if (grantResults.length > 0) {
                int index = 0;
                for (String permission : permissions) {

                    if (permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE) && (grantResults[index] == PackageManager.PERMISSION_GRANTED)) {
                        mPrimaryStorageReadGranted = true;
                        Timber.d("Permission READ_EXTERNAL_STORAGE Granted! Parse audio files");
                        mSwipeRefreshView.setRefreshing(true);
                        mAudioAdapter.removeAllItems();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            parsePrimary();
                        }
                        if (mExternalAccessGranted) {
                            List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
                            for (UriPermission p : list) {
                                parseExternal(p.getUri());
                            }
                        }
                        if (mAudioAdapter.getItemCount() > 0) {
                            mAudioMsgView.setVisibility(View.GONE);
                        } else {
                            mAudioMsgView.setVisibility(View.VISIBLE);
                        }
                        mSwipeRefreshView.setRefreshing(false);
                    }
                    if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE) && (grantResults[index] == PackageManager.PERMISSION_GRANTED)) {
                        mPrimaryStorageWriteGranted = true;
                    }
                    if (permission.equals(Manifest.permission.RECORD_AUDIO) && (grantResults[index] == PackageManager.PERMISSION_GRANTED)) {
                        mRecordAudioGranted = true;
                    }
                    index++;
                }
                if (mRecordAudioGranted && mPrimaryStorageWriteGranted) {
                    mRecordTitleView.setVisibility(View.VISIBLE);
                    mRecordCardView.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private final int GET_EXTERNAL_ACCESS=1;

    public void scanExternal(View view) {
        mSwipeRefreshView.setRefreshing(true);
        List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
        if (list.isEmpty() || ! mAudioAdapter.isPermissionGranted(list) || mNewUSBDeviceAttached) {
            // At least one element in list not granted (or list empty)
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, GET_EXTERNAL_ACCESS);
        } else {
            mAudioAdapter.removeAllExternalItems();
            for (UriPermission permission:list){
                parseExternal(permission.getUri());
            }

            if (mAudioAdapter.getItemCount() > 0) {
                mAudioMsgView.setVisibility(View.GONE);
            } else {
                mAudioMsgView.setVisibility(View.VISIBLE);
            }
            mSwipeRefreshView.setRefreshing(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == GET_EXTERNAL_ACCESS) {
            mAudioAdapter.removeAllExternalItems();
            List<UriPermission> list = getContentResolver().getPersistedUriPermissions();
            if (! list.isEmpty()) {
                for (UriPermission permission:list){
                    parseExternal(permission.getUri());
                }
            }
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    Uri treeUri = data.getData();
                    if (treeUri != null) {
                        getContentResolver().takePersistableUriPermission(treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        parseExternal(treeUri);
                        mExternalAccessGranted = true;
                    }
                }
                mNewUSBDeviceAttached = false;
            }
            if (mAudioAdapter.getItemCount() > 0) {
                mAudioMsgView.setVisibility(View.GONE);
            } else {
                mAudioMsgView.setVisibility(View.VISIBLE);
            }
            mSwipeRefreshView.setRefreshing(false);
        }
    }

    private void parseExternal(Uri treeUri) {
        if (checkExternalStorageExists(treeUri)) {
            DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
            // List all existing files inside picked directory
            if (pickedDir != null && pickedDir.exists()) {
                Toast.makeText(this, "Parse USB root directory", Toast.LENGTH_SHORT).show();
                ArrayList<AudioDetails> audioList = new ArrayList<>();
                for (DocumentFile file : pickedDir.listFiles()) {
                    if (!file.isDirectory()) {
                        if (isAudioFile(file)) {
                            Timber.d("File available %s", file.getName());
                            AudioDetails audio = new AudioDetails();
                            audio.setAudioName(file.getName());
                            audio.setAudioUri(file.getUri());
                            audio.setAudioUriPermission(treeUri);
                            audio.setVolume(AudioDetails.AUDIO_VOLUME_EXTERNAL);

                            try {
                                ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(file.getUri(), "r");
                                if (fd != null) {
                                    FileDescriptor descriptor = fd.getFileDescriptor();
                                    try (FileInputStream is = new FileInputStream(descriptor)) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                                                mmr.setDataSource(is.getFD());
                                                audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                                audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                                String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                                if (title != null && !title.isEmpty()) {
                                                    audio.setAudioName(title);
                                                }
                                            }
                                        } else {
                                            // close method doesn't exist for Android P (try will call automatically the close method)
                                            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                            mmr.setDataSource(is.getFD());
                                            audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                            audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                            if (title != null && !title.isEmpty()) {
                                                audio.setAudioName(title);
                                            }
                                        }
                                    } catch (Exception e) {
                                        Timber.e("File not available : %s", e.getMessage());
                                        audio.setAudioArtist(null);
                                    }

                                    try {
                                        fd.close();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            } catch (FileNotFoundException e) {
                                e.printStackTrace();
                            }
                            audioList.add(audio);
                        }
                    } else {
                        if (file.getName() != null && file.getName().equals(EXTERNAL_MUSIC_DIR_NAME) && file.isDirectory()) {
                            Toast.makeText(this, "Parse USB " + EXTERNAL_MUSIC_DIR_NAME + " directory if exists", Toast.LENGTH_SHORT).show();
                            for (DocumentFile music : file.listFiles()) {
                                if (!music.isDirectory()) {
                                    if (isAudioFile(music)) {
                                        Timber.d("File available %s", music.getName());
                                        AudioDetails audio = new AudioDetails();
                                        audio.setAudioName(music.getName());
                                        audio.setAudioUri(music.getUri());
                                        audio.setAudioUriPermission(treeUri);
                                        audio.setVolume(AudioDetails.AUDIO_VOLUME_EXTERNAL);

                                        try {
                                            ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(music.getUri(), "r");
                                            if (fd != null) {
                                                FileDescriptor descriptor = fd.getFileDescriptor();
                                                try (FileInputStream is = new FileInputStream(descriptor)) {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                                                            mmr.setDataSource(is.getFD());
                                                            audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                                            audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                                            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                                            if (title != null && !title.isEmpty()) {
                                                                audio.setAudioName(title);
                                                            }
                                                        }
                                                    } else {
                                                        // close method doesn't exist for Android P (try will call automatically the close method)
                                                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                                        mmr.setDataSource(is.getFD());
                                                        audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                                        audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                                        String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                                        if (title != null && !title.isEmpty()) {
                                                            audio.setAudioName(title);
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    Timber.e("File not available : %s", e.getMessage());
                                                    audio.setAudioArtist(null);
                                                }

                                                try {
                                                    fd.close();
                                                } catch (IOException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        } catch (FileNotFoundException e) {
                                            e.printStackTrace();
                                        }
                                        audioList.add(audio);
                                    }
                                }
                            }
                        }
                    }
                }
                if (!audioList.isEmpty()) {
                    mAudioAdapter.addItems(audioList);
                }
            }
        }
    }

    @RequiresApi(29)
    private void parsePrimary(){

        if (mPrimaryStorageReadGranted && isPrimaryAvailable()) {

            String[] projection;
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                projection = new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.TITLE,
                        MediaStore.Audio.AudioColumns.DURATION,
                        MediaStore.Audio.AudioColumns.ARTIST
                };
            } else {
                projection = new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.TITLE,
                        MediaStore.Audio.AudioColumns.DURATION
                };
            }

            ContentResolver contentResolver = getContentResolver();
            Uri volumeUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Cursor cursor = contentResolver.query(volumeUri,
                    projection, null, null,null);

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    ArrayList<AudioDetails> audioList = new ArrayList<>();
                    do {
                        AudioDetails audio = new AudioDetails();

                        int id = cursor.getInt(0);
                        audio.setAudioUri(Uri.withAppendedPath(volumeUri,Integer.toString(id)));

                        audio.setAudioName(cursor.getString(1));
                        audio.setAudioDuration(cursor.getLong(2));

                        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
                            audio.setAudioArtist(cursor.getString(3));
                        }

                        // no associated permission URI for primary (generic external storage access used)
                        audio.setAudioUriPermission(null);
                        audio.setVolume(AudioDetails.AUDIO_VOLUME_EXTERNAL_PRIMARY);

                        audioList.add(audio);
                    } while (cursor.moveToNext());

                    if(! audioList.isEmpty()) {
                        mAudioAdapter.addItems(audioList);
                    }
                }
                cursor.close();
            }
        }
    }

    private void parseInternal() {
        File folder = new File(getFilesDir() + "/" + INTERNAL_RECORD_DIR_NAME);
        if (folder.exists()) {
            DocumentFile document = DocumentFile.fromFile(folder);
            ArrayList<AudioDetails> audioList = new ArrayList<>();
            for (DocumentFile file : document.listFiles()) {
                if (file.isFile()) {
                    AudioDetails audio = new AudioDetails();
                    audio.setAudioUri(file.getUri());
                    audio.setVolume(AudioDetails.AUDIO_VOLUME_INTERNAL);

                    try {
                        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(file.getUri(), "r");
                        if (fd != null) {
                            FileDescriptor descriptor = fd.getFileDescriptor();
                            try (FileInputStream is = new FileInputStream(descriptor)) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                                        mmr.setDataSource(is.getFD());
                                        audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                        audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                        String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                        if (title != null && !title.isEmpty()) {
                                            audio.setAudioName(title);
                                        } else {
                                            audio.setAudioName(file.getName());
                                        }
                                    }
                                } else {
                                    // close method doesn't exist for Android P (try will call automatically the close method)
                                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                    mmr.setDataSource(is.getFD());
                                    audio.setAudioDuration(Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                                    audio.setAudioArtist(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                                    String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                                    if (title != null && !title.isEmpty()) {
                                        audio.setAudioName(title);
                                    } else {
                                        audio.setAudioName(file.getName());
                                    }
                                }
                            } catch (Exception e) {
                                Timber.e("File not available : %s", e.getMessage());
                                audio.setAudioName(file.getName());
                            }

                            audioList.add(audio);

                            try {
                                fd.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }

            if (!audioList.isEmpty()) {
                mAudioAdapter.addItems(audioList);
            }
        }
    }

    private boolean isPrimaryAvailable() {
        String state = Environment.getExternalStorageState();
        return state.equals(Environment.MEDIA_MOUNTED);
    }

    private boolean isAudioFile(DocumentFile file) {
        ContentResolver cr = getContentResolver();
        String type = cr.getType(file.getUri());
        return type != null && type.startsWith("audio");
    }

    private void initPlayer() {
        mPlayer = new MediaPlayer();

        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mPlayer.isPlaying()) {
                            mAudioAdapter.updatePosition(mAudioListView, mPlayer.getCurrentPosition());
                        }
                    }
                });
            }
        },0,1000);
    }

    private boolean isStorageAvailable(AudioDetails audio) {
        if (audio.isVolumePrimary()) {
            if(! isPrimaryAvailable()) {
                Toast.makeText(this, "External primary storage no more available (SD card), remove all associated files from list", Toast.LENGTH_SHORT).show();
                mAudioAdapter.removeAllPrimaryItems();
                return false;
            }
            return true;
        }
        if (audio.isVolumeExternal()) {
            if (! checkExternalStorageExists(audio.getAudioUriPermission())) {
                Toast.makeText(this, "External storage no more available (USB key), remove all associated files from list", Toast.LENGTH_SHORT).show();
                mAudioAdapter.removeAllExternalItems();
                getContentResolver().releasePersistableUriPermission(audio.getAudioUriPermission(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                mExternalAccessGranted = false;
                return false;
            } else {
                return true;
            }
        }
        return true;
    }

    private boolean checkExternalStorageExists(Uri permission) {
        StorageManager storageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (storageManager != null) {
            List<StorageVolume> volumes = storageManager.getStorageVolumes();
            for (StorageVolume v:volumes){
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!v.isPrimary()) {
                        String desc = v.getUuid();
                        String volume = permission.getLastPathSegment();
                        if (desc != null && volume != null && volume.startsWith(desc)) {
                            return true;
                        }
                    }
                } else {
                    // Manage primary as standard external storage
                    String desc = v.getUuid();
                    String volume = permission.getLastPathSegment();
                    if (desc != null && volume != null && volume.startsWith(desc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void onClick(AudioDetails audio, boolean togglePlay) {
        if (audio != null) {
            if (isStorageAvailable(audio)) {
                Uri audioUri = audio.getAudioUri();
                if (togglePlay) {
                    if (!audioUri.equals(mCurrentAudioUri)) {
                        mPlayer.reset();
                        mCurrentAudioUri = audioUri;
                        try {
                            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                                @Override
                                public boolean onError(MediaPlayer mp, int what, int extra) {
                                    Timber.e("MediaPlayer error what %d extra %d", what, extra);
                                    mAudioAdapter.removeItem(mCurrentAudioUri);
                                    mAudioAdapter.resetPlayer();
                                    mp.reset();
                                    return true;
                                }
                            });

                            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                @Override
                                public void onCompletion(MediaPlayer mp) {
                                    Timber.d("Audio Playback END");
                                    mAudioAdapter.playerCompletion(mAudioListView);
                                }
                            });

                            mPlayer.setDataSource(getApplicationContext(), audioUri);
                            mPlayer.prepare();

                            mAudioAdapter.hidePreviousPosition(mAudioListView);
                            mAudioAdapter.updateDuration(mAudioListView, mPlayer.getDuration());
                            mAudioAdapter.updatePosition(mAudioListView, mPlayer.getCurrentPosition());

                        } catch (IOException e) {
                            e.printStackTrace();
                            return;
                        }
                    }

                    Timber.d("Audio Playback START");
                    mPlayer.start();
                } else {
                    if (mPlayer.isPlaying()) {
                        // consider that it's always a togglePlay on current URI (not checked again)
                        Timber.d("Audio Playback PAUSE");
                        mPlayer.pause();
                    }
                }
            } else {
                if ((togglePlay)  && (! audio.getAudioUri().equals(mCurrentAudioUri))){
                    mAudioAdapter.hidePreviousPosition(mAudioListView);
                    mAudioAdapter.updatePosition(mAudioListView,0);
                    mAudioAdapter.resetPlayer();
                }
            }

        } else {
            Toast.makeText(this, "only one playback possible at once currently", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDelete(AudioDetails audio) {
        Uri audioUri = audio.getAudioUri();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isPrimaryAvailable() && audio.isVolumePrimary()) {
                Toast.makeText(this, "File " + audio.getAudioName() + " deleted", Toast.LENGTH_SHORT).show();
                mAudioAdapter.removeItem(audioUri);
                // TODO use ContentUris.parseId(audioUri) instead returning id from Uri (Long format)
                removeFromMediaStore(audioUri.getLastPathSegment());
            }
        }
        if (audio.isVolumeInternal()) {
            if (audioUri.getPath() != null) {
                File delete = new File(audioUri.getPath());
                if (delete.exists()) {
                    if (delete.delete()) {
                        mAudioAdapter.removeItem(audioUri);
                        Toast.makeText(this, "File " + audio.getAudioName() + " deleted", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }

        if (mAudioAdapter.getItemCount() > 0) {
            mAudioMsgView.setVisibility(View.GONE);
        } else {
            mAudioMsgView.setVisibility(View.VISIBLE);
        }
    }

    private String now2DateTime () {
        Date date = Calendar.getInstance().getTime();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
        return formatter.format(date);
    }

    public void toggleRecord(View view) {
        String fileName = mRecordEditTextView.getText().toString();
        if (fileName.length() > 0) {

            // Add date to insure unique name for file
            String fullName = fileName.concat("_staudio_").concat(now2DateTime()).concat(".m4a");

            if (mMediaRecorder == null) {
                mMediaRecorder = new MediaRecorder();
            }

            if (!mMediaRecorderState) {
                mRecordEditTextView.setEnabled(false);
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isPrimaryAvailable()) {
                        File[] directories = getExternalMediaDirs();
                        if (directories.length > 0) {
                            mMediaRecorderFile = new File(directories[0], fullName);
                        } else {
                            Timber.e("No media directory available in primary storage");
                            return;
                        }
                        mMediaRecorderFilePrimary = true;
                    } else {
                        File folder = new File(getFilesDir() + "/" + INTERNAL_RECORD_DIR_NAME);
                        if (!folder.exists()) {
                            if (folder.mkdir()) {
                                Timber.d("Internal storage directory created: %s", folder.getName());
                            }
                        }
                        mMediaRecorderFile = new File(folder, fullName);
                        mMediaRecorderFilePrimary = false;
                    }
                } else {
                    File folder = new File(getFilesDir() + "/" + INTERNAL_RECORD_DIR_NAME);
                    if (!folder.exists()) {
                        if (folder.mkdir()) {
                            Timber.d("Internal storage directory created: %s", folder.getName());
                        }
                    }
                    mMediaRecorderFile = new File(folder, fullName);
                    mMediaRecorderFilePrimary = false;
                }
                mMediaRecorder.setOutputFile(mMediaRecorderFile);
                mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

                try {
                    mMediaRecorder.prepare();
                    mMediaRecorder.start();
                    mMediaRecorderState = true;

                    mRecordButtonView.setImageResource(R.drawable.ic_pause_white);
                    mMediaRecorderStarted = true;
                } catch (IOException e) {
                    Timber.e("Media Recorder prepare error: %s", e.getMessage());
                    e.printStackTrace();
                }
            } else {
                if (!mMediaRecorderStarted) {
                    mMediaRecorder.resume();
                    mRecordButtonView.setImageResource(R.drawable.ic_pause_white);
                    mMediaRecorderStarted = true;
                } else {
                    mMediaRecorder.pause();
                    mRecordButtonView.setImageResource(R.drawable.ic_record_white);
                    mMediaRecorderStarted = false;
                }
            }

        } else {
            Toast.makeText(this, "Please enter a file name", Toast.LENGTH_LONG).show();
        }
    }

    public void cancelRecord(View view) {
        if (mMediaRecorder != null) {
            if (mMediaRecorderState) {
                mMediaRecorder.pause();
                mRecordButtonView.setImageResource(R.drawable.ic_record_white);
                mMediaRecorderStarted = false;
            }
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mRecordEditTextView.getText().clear();
            mRecordEditTextView.setEnabled(true);
            mMediaRecorderState = false;
            mMediaRecorderStarted = false;

            if (mMediaRecorderFile.delete()) {
                Toast.makeText(this, "Record canceled", Toast.LENGTH_SHORT).show();
            } else {
                Timber.e("Not possible to delete file");
            }
        }
    }

    public void saveRecord(View view) {
        if (mMediaRecorder != null) {
            if (mMediaRecorderState) {
                mMediaRecorder.pause();
                mRecordButtonView.setImageResource(R.drawable.ic_record_white);
                mMediaRecorderStarted = false;
            }
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mRecordEditTextView.getText().clear();
            mRecordEditTextView.setEnabled(true);

            if (mMediaRecorderFile.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (mMediaRecorderFilePrimary) {
                        addInMediaStore();
                    } else {
                        AudioDetails audio = new AudioDetails();

                        DocumentFile file = DocumentFile.fromFile(mMediaRecorderFile);
                        audio.setAudioUri(file.getUri());
                        audio.setAudioName(file.getName());

                        // no associated permission URI for internal
                        audio.setAudioUriPermission(null);
                        audio.setVolume(AudioDetails.AUDIO_VOLUME_INTERNAL);

                        mAudioAdapter.addItem(audio);
                        mAudioMsgView.setVisibility(View.GONE);
                    }
                } else {
                    AudioDetails audio = new AudioDetails();

                    DocumentFile file = DocumentFile.fromFile(mMediaRecorderFile);
                    audio.setAudioUri(file.getUri());
                    audio.setAudioName(file.getName());

                    // no associated permission URI for internal
                    audio.setAudioUriPermission(null);
                    audio.setVolume(AudioDetails.AUDIO_VOLUME_INTERNAL);

                    mAudioAdapter.addItem(audio);
                    mAudioMsgView.setVisibility(View.GONE);
                }
            }

            mMediaRecorderState = false;
            mMediaRecorderStarted = false;
        }
    }

    @RequiresApi(29)
    private void removeFromMediaStore(String id){
        getContentResolver().delete(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.MediaColumns._ID + " = ?",new String[]{id});
    }

    @RequiresApi(29)
    private void addInMediaStore(){
        if ((mMediaRecorderFile != null) && (mMediaRecorderFile.length() > 0)) {
            MediaScannerWrapper wrapper = new MediaScannerWrapper(getApplicationContext(), mMediaRecorderFile.getAbsolutePath(), "audio/mp4a-latm");
            wrapper.scan();
        }
    }

    @RequiresApi(29)
    private void addInList() {

        String[] projection;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.TITLE,
                    MediaStore.Audio.AudioColumns.DURATION,
                    MediaStore.Audio.AudioColumns.ARTIST
            };
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.TITLE,
                    MediaStore.Audio.AudioColumns.DURATION
            };
        }

        ContentResolver contentResolver = getContentResolver();
        Uri volumeUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // remove extension (not part of title)
        String fileName = mMediaRecorderFile.getName();
        String title = fileName.substring(0, fileName.lastIndexOf('.'));

        Cursor cursor = contentResolver.query(volumeUri,
                projection, MediaStore.MediaColumns.TITLE + " = ?", new String[]{title},null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                AudioDetails audio = new AudioDetails();

                int id = cursor.getInt(0);
                audio.setAudioUri(Uri.withAppendedPath(volumeUri, Integer.toString(id)));

                audio.setAudioName(cursor.getString(1));
                audio.setAudioDuration(cursor.getLong(2));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    audio.setAudioArtist(cursor.getString(3));
                }

                // no associated permission URI for primary (generic external storage access used)
                audio.setAudioUriPermission(null);
                audio.setVolume(AudioDetails.AUDIO_VOLUME_EXTERNAL_PRIMARY);

                mAudioAdapter.addItem(audio);
                mAudioMsgView.setVisibility(View.GONE);
            }
            cursor.close();
        }
    }

    @RequiresApi(29)
    public class MediaScannerWrapper implements MediaScannerConnection.MediaScannerConnectionClient {

        private final MediaScannerConnection mConnection;
        private final String mPath;
        private final String mMimeType;

        MediaScannerWrapper(Context ctx, String filePath, String mime){
            mPath = filePath;
            mMimeType = mime;
            mConnection = new MediaScannerConnection(ctx, this);
        }

        void scan(){
            mConnection.connect();
        }

        @Override
        public void onMediaScannerConnected() {
            mConnection.scanFile(mPath, mMimeType);
            Timber.d("Media file scanned: %s", mPath);
        }

        @Override
        public void onScanCompleted(String arg0, Uri arg1) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    addInList();
                }
            });
        }
    }
}
