package uz.xsoft.myapplication

import android.content.Context
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions

fun Context.checkPermission(vararg permissions: String, granted: () -> Unit) {
    val options = Permissions.Options()
    options.setCreateNewTask(true)
    Permissions.check(this, permissions, null, options, object : PermissionHandler() {
        override fun onGranted() {
            granted()
        }
    })
}

fun <T> Context.checkPermissionSuspend(permission: String ,granted: () -> Unit) {
    val options = Permissions.Options()
    options.setCreateNewTask(true)
    Permissions.check(this, arrayOf(permission), null, options, object : PermissionHandler() {
        override fun onGranted() {
            granted()
        }
    })
}
