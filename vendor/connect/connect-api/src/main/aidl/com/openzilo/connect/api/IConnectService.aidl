package com.openzilo.connect.api;
import android.os.Bundle;
import android.app.PendingIntent;
import com.openzilo.connect.api.IConnectCallback;
interface IConnectService {
    PendingIntent requestAuthorization(in String[] scopes);
    Bundle getStatus();
    void execute(String requestId, String operation, in Bundle arguments, IConnectCallback callback);
    void subscribe(in String[] topics, IConnectCallback callback);
    void unsubscribe(IConnectCallback callback);
    void cancel(String requestId, IConnectCallback callback);
    void closeClient(IConnectCallback callback);
}
