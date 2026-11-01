package android.support.v4.media;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Media compat's MediaItem, only to read one another app sends (a search's answer from BitChord, see MusicEngine): it
 * crosses by this name, as its flags and Android's own MediaDescription.
 */
public final class MediaBrowserCompat {
    private MediaBrowserCompat() {}

    public static final class MediaItem implements Parcelable {
        public final MediaBrowser.MediaItem item;

        MediaItem(Parcel in) {
            int flags = in.readInt();
            item = new MediaBrowser.MediaItem(MediaDescription.CREATOR.createFromParcel(in), flags);
        }

        @Override public int describeContents() {
            return 0;
        }

        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeInt(item.getFlags());
            item.getDescription().writeToParcel(out, flags);
        }

        public static final Creator<MediaItem> CREATOR = new Creator<MediaItem>() {
            @Override public MediaItem createFromParcel(Parcel in) {
                return new MediaItem(in);
            }

            @Override public MediaItem[] newArray(int size) {
                return new MediaItem[size];
            }
        };
    }
}
