package com.dream.video.cache.demo;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.blankj.utilcode.util.LogUtils;
import com.permissionx.guolindev.PermissionX;

import java.util.ArrayList;
import java.util.List;

public class StartActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestPermissions();
    }

    private void requestPermissions() {
        List<String> permissionList = new ArrayList<>();
        permissionList.add(Manifest.permission.INTERNET);
        permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        PermissionX.init(this)
                .permissions(permissionList)
                .explainReasonBeforeRequest()
                .onExplainRequestReason((scope, deniedList, beforeRequest) -> {
                    if (beforeRequest) {
                        scope.showRequestReasonDialog(deniedList,
                                "即将申请的权限是程序必须依赖的权限",
                                "我已明白");
                    } else {
                        scope.showRequestReasonDialog(deniedList,
                                "即将重新申请的权限是程序必须依赖的权限",
                                "我已明白");
                    }
                })
                .onForwardToSettings((scope, deniedList) ->
                        scope.showForwardToSettingsDialog(deniedList,
                                "您需要去应用程序设置当中手动开启权限",
                                "去设置"))
                .request((allGranted, grantedList, deniedList) -> {
                    LogUtils.d("allGranted:" + allGranted +
                            " grantedList:" + grantedList +
                            " deniedList:" + deniedList);
                    if (allGranted ||
                            (deniedList.size() == 1 &&
                                    deniedList.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))) {
                        onAllPermissionsGranted();
                    }
                });
    }

    private void onAllPermissionsGranted() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
