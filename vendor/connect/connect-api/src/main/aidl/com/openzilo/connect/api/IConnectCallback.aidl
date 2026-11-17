package com.openzilo.connect.api;
import android.os.Bundle;
oneway interface IConnectCallback {
    void onResult(String requestId, in Bundle result);
    void onEvent(String topic, in Bundle event);
}
