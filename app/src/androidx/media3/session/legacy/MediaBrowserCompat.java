package androidx.media3.session.legacy;

import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Media3's copy of media compat's MediaItem, by the name Media3 gives it (what BitChord's media service may send a
 * search's answer as, see MusicEngine): read as android.support.v4.media.MediaBrowserCompat.MediaItem is.
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
