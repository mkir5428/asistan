package com.asistan.cip;

final class TransferCredentials {
    final String serverUrl;
    final String pairingId;
    final String deviceToken;

    TransferCredentials(String serverUrl, String pairingId, String deviceToken) {
        this.serverUrl = serverUrl;
        this.pairingId = pairingId;
        this.deviceToken = deviceToken;
    }
}
