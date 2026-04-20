package com.kizeo.kzstorereader.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receiver que cancela la transferencia FTP activa desde la notificación.
 * Accede directamente a FtpActivity.activeTransfer (referencia estática).
 */
public class CancelTransferReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Cancelar la tarea activa directamente sin abrir la Activity
        FtpActivity.TransferTask task = FtpActivity.activeTransfer;
        if (task != null) {
            task.cancel();
        }
    }
}