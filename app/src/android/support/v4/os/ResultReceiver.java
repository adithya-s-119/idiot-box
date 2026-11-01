package android.support.v4.os;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Media compat's result receiver, as it crosses to another app: a binder that app answers on (IResultReceiver's one
 * call, send). Idiot Box sends it with a search to BitChord's media service (MusicEngine), which reads it back as
 * its own copy of this class: so it has this name, and only writes itself.
 */
public final class ResultReceiver implements Parcelable {
    public interface Answer {
        void answer(int code, Bundle data);
    }

    private static final String DESCRIPTOR = "android.support.v4.os.IResultReceiver";
    private final Binder binder;

    /** [answer] is called on the main thread. */
    public ResultReceiver(Answer answer) {
        Handler main = new Handler(Looper.getMainLooper());
        binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(DESCRIPTOR);
                int result = data.readInt();
                Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                main.post(() -> answer.answer(result, b));
                return true;
            }
        };
        binder.attachInterface(null, DESCRIPTOR);
    }

    @Override public int describeContents() {
        return 0;
    }

    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(binder);
    }
}
