package com.qw.photo.work

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.qw.photo.CoCoConfigs
import com.qw.photo.DevUtil
import com.qw.photo.Utils
import com.qw.photo.agent.IContainer
import com.qw.photo.callback.CoCoCallBack
import com.qw.photo.constant.Constant
import com.qw.photo.exception.BadConvertException
import com.qw.photo.exception.BaseException
import com.qw.photo.exception.NoFileProvidedException
import com.qw.photo.functions.CropBuilder
import com.qw.photo.pojo.CropResult
import com.qw.photo.pojo.DisposeResult
import com.qw.photo.pojo.PickResult
import com.qw.photo.pojo.TakeResult
import java.io.File

/**
 * The Crop Worker to crop image
 * @author Hebe
 * Date: 2020/10/9
 */

class CropWorker(handler: IContainer, builder: CropBuilder) :
    BaseWorker<CropBuilder, CropResult>(handler, builder) {

    override fun start(formerResult: Any?, callBack: CoCoCallBack<CropResult>) {
        addConfigFromCoCoConfig()
        try {
            convertFormerResultToCurrent(formerResult)
        } catch (e: Exception) {
            callBack.onFailed(e)
            return
        }
        if (mParams.cropCallBack != null) {
            mParams.cropCallBack!!.onStart()
        }
        if (mParams.originFile == null) {
            callBack.onFailed(BaseException("crop file is null"))
            return
        }
        val activity = iContainer.provideActivity()
        activity ?: return
        val intent: Intent?
        val uri =
            Utils.createUriFromFile(iContainer.provideActivity() as Context, mParams.originFile!!)
        try {
            intent = routeToCrop(activity, uri, mParams.cropWidth, mParams.cropHeight)
        } catch (e: Exception) {
            callBack.onFailed(e)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.grantUriPermission(
                activity.packageName,
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            activity.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        iContainer.startActivityResult(
            intent,
            Constant.REQUEST_CODE_CORP_IMAGE
        ) { _: Int, resultCode: Int, resultData: Intent? ->
            handleResult(resultCode, resultData, callBack)
        }
    }

    private fun addConfigFromCoCoConfig() {
        if (null != CoCoConfigs.cropsResultFile) {
            mParams.savedResultFile = File(CoCoConfigs.cropsResultFile)
        }
    }

    private fun handleResult(
        resultCode: Int,
        resultData: Intent?,
        callBack: CoCoCallBack<CropResult>,
    ) {
        if (resultCode == Activity.RESULT_CANCELED) {
            if (null != mParams.cropCallBack) {
                mParams.cropCallBack!!.onCancel()
            }
            return
        }
        val result = CropResult()
        result.originFile = mParams.originFile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val imageBitmap = resultData?.data.let {
                var uri = it
                if (it == null && resultData?.data?.action != null) {
                    uri = Uri.parse(resultData?.data?.action)
                }
                val inputStream =
                    iContainer.provideActivity()!!.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(inputStream)
            }
            result.cropBitmap = imageBitmap
        } else {
            result.savedFile = mParams.savedResultFile
            result.cropBitmap = Utils.getBitmapFromFile(mParams.savedResultFile!!.absolutePath)!!
        }
        if (null != mParams.cropCallBack) {
            mParams.cropCallBack!!.onFinish(result)
        }
        callBack.onSuccess(result)
    }

    private fun routeToCrop(context: Context, uri: Uri?, cropWidth: Int, cropHeight: Int): Intent {
        val intent = Intent("com.android.camera.action.CROP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intent.setDataAndType(uri, "image/*")
        intent.putExtra("crop", true)
        if (Build.MANUFACTURER == Constant.ROM_HUA_WEI) {
            if (cropWidth == cropHeight) {
                intent.putExtra("aspectX", 9998)
                intent.putExtra("aspectY", 9999)
            } else {
                intent.putExtra("aspectX", cropWidth)
                intent.putExtra("aspectY", cropHeight)
            }
        } else {
            intent.putExtra("aspectX", cropWidth)
            intent.putExtra("aspectY", cropHeight)
        }
        intent.putExtra("outputX", cropWidth)
        intent.putExtra("outputY", cropHeight)
        intent.putExtra("return-data", false)
        //if did not set a file to save result ,this file will create in app package location automatic
        if (null == mParams.savedResultFile) {
            mParams.savedResultFile = Utils.createSDCardFile(context)
        }
        val outPutUri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues()
                )
            } else {
                Uri.fromFile(mParams.savedResultFile)
            }
        intent.putExtra(
            MediaStore.EXTRA_OUTPUT, outPutUri
        )
        DevUtil.d(Constant.TAG, mParams.savedResultFile!!.absolutePath)
        return intent
    }

    private fun convertFormerResultToCurrent(
        formerResult: Any?
    ) {
        if (null == formerResult) {
            return
        }
        if (formerResult is TakeResult) {
            mParams.originFile = formerResult.savedFile!!
        }
        if (formerResult is PickResult) {
            val localPath =
                Utils.uriToImagePath(iContainer.provideActivity()!!, formerResult.originUri)
            if (!localPath.isNullOrBlank()) {
                val f = File(localPath)
                if (f.exists()) {
                    mParams.originFile = f
                } else {
                    throw BadConvertException(formerResult)
                }
            }
            if (localPath.isNullOrBlank()) {
                throw BadConvertException(formerResult)
            }
        }
        if (formerResult is DisposeResult) {
            if (null == formerResult.savedFile) {
                throw NoFileProvidedException("DisposeBuilder.fileToSaveResult")
            }
            mParams.originFile = formerResult.savedFile
        }
    }

}
