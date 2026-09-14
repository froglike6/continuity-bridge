package android.os;

public interface IBinder {
    interface DeathRecipient { void binderDied(); }
    boolean isBinderAlive();
    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
}
