/****************************************************************************************
 * Copyright (c) 2013 Bibek Shrestha <bibekshrestha@gmail.com>                          *
 * Copyright (c) 2013 Zaur Molotnikov <qutorial@gmail.com>                              *
 * Copyright (c) 2013 Nicolas Raoul <nicolas.raoul@gmail.com>                           *
 * Copyright (c) 2013 Flavio Lerda <flerda@gmail.com>                                   *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
package com.ichi2.anki.multimediacard.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.multimediacard.IMultimediaEditableNote
import com.ichi2.anki.multimediacard.fields.*
import com.ichi2.anki.showThemedToast
import com.ichi2.audio.AudioRecordingController
import com.ichi2.audio.AudioRecordingController.Companion.isAudioRecordingSaved
import com.ichi2.audio.AudioRecordingController.Companion.isRecording
import com.ichi2.audio.AudioRecordingController.Companion.setEditorStatus
import com.ichi2.compat.CompatHelper.Companion.getSerializableCompat
import com.ichi2.utils.KotlinCleanup
import com.ichi2.utils.Permissions
import timber.log.Timber
import java.io.File
import java.text.DecimalFormat

@KotlinCleanup("lateinit")
class MultimediaEditFieldActivity :
    AnkiActivity(),
    OnRequestPermissionsResultCallback,
    DefaultLifecycleObserver {
    private lateinit var field: IField
    private lateinit var note: IMultimediaEditableNote
    private var fieldIndex = 0

    private var audioRecordingController = AudioRecordingController()
    private var isAudioUIInitialized = false

    @get:VisibleForTesting
    var fieldController: IFieldController? = null
        private set

    /**
     * Cached copy of the current request to change a field
     * Used to access past state from OnRequestPermissionsResultCallback
     */
    private var currentChangeRequest: ChangeUIRequest? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super<AnkiActivity>.onCreate(savedInstanceState)
        var controllerBundle: Bundle? = null
        if (savedInstanceState != null) {
            Timber.i("onCreate - saved bundle exists")
            val b = savedInstanceState.getBoolean(BUNDLE_KEY_SHUT_OFF, false)
            controllerBundle = savedInstanceState.getBundle("controllerBundle")
            if (controllerBundle == null && b) {
                Timber.i("onCreate - saved bundle has BUNDLE_KEY_SHUT_OFF and no controller bundle, terminating")
                finishCancel()
                return
            }
        }
        setTitle(R.string.title_activity_edit_text)
        setContentView(R.layout.multimedia_edit_field_activity)
        val mainView = findViewById<View>(android.R.id.content)
        enableToolbar(mainView)
        val intent = this.intent
        val extras = getFieldFromIntent(intent)
        if (extras == null) {
            showThemedToast(this, getString(R.string.multimedia_editor_failed), false)
            finishCancel()
            return
        }
        fieldIndex = extras.first
        field = extras.second
        note = extras.third

        val savedImageUri = getIntentImageUri()
        if (savedImageUri != null) {
            intentEditImage()
        }
        onBack()
        recreateEditingUi(ChangeUIRequest.init(field), controllerBundle)
    }

    fun getIntentImageUri(): Uri? {
        return IntentCompat.getParcelableExtra(intent, INTENT_IMAGE_URI, Uri::class.java)
    }

    private fun intentEditImage() {
        recreateEditingUi(ChangeUIRequest.uiChange(ImageField()), intentImgEdit = true)
    }

    // in case media is saved by view button then allows it to be inserted into the filed
    private fun onBack() {
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (isAudioUIInitialized) {
                done()
            } else {
                saveAndExit()
            }
        }
    }

    private fun finishCancel() {
        Timber.d("Completing activity via finishCancel()")
        val resultData = Intent()
        setResult(RESULT_CANCELED, resultData)
        finish()
    }

    private fun hasPerformedPermissionRequestForField(field: IField): Boolean {
        // Request permission to record if audio field
        if (field is AudioRecordingField && !Permissions.canRecordAudio(this)) {
            Timber.d("Requesting Audio Permissions")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return true
        }
        return false
    }

    private fun setupUIController(fieldController: IFieldController, savedInstanceState: Bundle?) {
        fieldController.apply {
            setField(field)
            setFieldIndex(fieldIndex)
            setNote(note)
            setEditingActivity(this@MultimediaEditFieldActivity)
            loadInstanceState(savedInstanceState)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        if (isAudioUIInitialized) audioRecordingController.onViewFocusChanged()
    }

    private fun recreateEditingUi(newUI: ChangeUIRequest, savedInstanceState: Bundle? = null, intentImgEdit: Boolean = false) {
        Timber.d("recreateEditingUi()")

        // Permissions are checked async, save our current state to allow continuation
        currentChangeRequest = newUI

        if (isAudioUIInitialized) audioRecordingController.onViewFocusChanged()

        // If we went through the permission check once, we don't need to do it again.
        // As we only get here a second time if we have the required permissions
        if (newUI.requiresPermissionCheck && hasPerformedPermissionRequestForField(newUI.field)) {
            newUI.markAsPermissionRequested()
            return
        }
        val fieldController = createControllerForField(newUI.field)
        if (intentImgEdit) {
            (fieldController as? BasicImageFieldController)?.directImageEdit = true
        }
        UIRecreationHandler.onPreFieldControllerReplacement(this.fieldController)
        this.fieldController = fieldController
        field = newUI.field
        setupUIController(this.fieldController!!, savedInstanceState)
        val linearLayout = findViewById<LinearLayout>(R.id.LinearLayoutInScrollViewFieldEdit)
        linearLayout.removeAllViews()
        fieldController.createUI(this, linearLayout)
        UIRecreationHandler.onPostUICreation(newUI, this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Timber.d("onCreateOptionsMenu() - mField.getType() = %s", field.type)
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.activity_edit_text, menu)
        menu.findItem(R.id.multimedia_edit_field_to_text).isVisible = field.type !== EFieldType.TEXT
        menu.findItem(R.id.multimedia_edit_field_to_audio).isVisible = field.type !== EFieldType.AUDIO_RECORDING
        menu.findItem(R.id.multimedia_edit_field_to_audio_clip).isVisible = field.type !== EFieldType.MEDIA_CLIP
        menu.findItem(R.id.multimedia_edit_field_to_image).isVisible = field.type !== EFieldType.IMAGE
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.multimedia_edit_field_to_text -> {
                Timber.i("To text field button pressed")
                toTextField()
                return true
            }
            R.id.multimedia_edit_field_to_image -> {
                Timber.i("To image button pressed")
                toImageField()
                return true
            }
            R.id.multimedia_edit_field_to_audio -> {
                Timber.i("To audio recording button pressed")
                toAudioRecordingField()
                return true
            }
            R.id.multimedia_edit_field_to_audio_clip -> {
                Timber.i("To audio clip button pressed")
                toAudioClipField()
                return true
            }
            R.id.multimedia_edit_field_done -> {
                Timber.i("Save button pressed")
                if (isAudioUIInitialized && isRecording) {
                    audioRecordingController.stopAndSaveRecording()
                }
                done()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    @KotlinCleanup("rename: bChangeToText")
    private fun done() {
        var bChangeToText = false
        if (field.type === EFieldType.IMAGE) {
            if (field.imagePath == null) {
                bChangeToText = true
            }
            if (!bChangeToText) {
                val f = File(field.imagePath!!)
                if (!f.exists()) {
                    bChangeToText = true
                } else {
                    val length = f.length()
                    if (length > IMAGE_LIMIT) {
                        showLargeFileCropDialog((1.0 * length / IMAGE_LIMIT).toFloat())
                        return
                    }
                }
            }
        } else if (field.type === EFieldType.AUDIO_RECORDING && isAudioRecordingSaved) {
            if (field.audioPath == null) {
                bChangeToText = true
            }
            if (!bChangeToText) {
                val f = File(field.audioPath!!)
                if (!f.exists()) {
                    bChangeToText = true
                }
            }
        }
        fieldController!!.onDone()
        saveAndExit(bChangeToText)
    }

    private fun toAudioRecordingField() {
        if (field.type !== EFieldType.AUDIO_RECORDING) {
            val request = ChangeUIRequest.uiChange(AudioRecordingField())
            recreateEditingUi(request)
        }
    }

    private fun toAudioClipField() {
        if (field.type !== EFieldType.MEDIA_CLIP) {
            val request = ChangeUIRequest.uiChange(MediaClipField())
            recreateEditingUi(request)
        }
    }

    private fun toImageField() {
        if (field.type !== EFieldType.IMAGE) {
            val request = ChangeUIRequest.uiChange(ImageField())
            recreateEditingUi(request)
        }
    }

    private fun toTextField() {
        if (field.type !== EFieldType.TEXT) {
            val request = ChangeUIRequest.uiChange(TextField())
            recreateEditingUi(request)
        }
    }

    private fun recreateEditingUIUsingCachedRequest() {
        Timber.d("recreateEditingUIUsingCachedRequest()")
        if (currentChangeRequest == null) {
            cancelActivityWithAssertionFailure("mCurrentChangeRequest should be set before using cached request")
            return
        }
        recreateEditingUi(currentChangeRequest!!)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (currentChangeRequest == null) {
            cancelActivityWithAssertionFailure("mCurrentChangeRequest should be set before requesting permissions")
            return
        }
        Timber.d("onRequestPermissionsResult. Code: %d", requestCode)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION && permissions.size == 1) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                recreateEditingUIUsingCachedRequest()
                return
            }
            showThemedToast(
                this,
                resources.getString(R.string.multimedia_editor_audio_permission_refused),
                true
            )
            UIRecreationHandler.onRequiredPermissionDenied(currentChangeRequest!!, this)
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION && permissions.size == 1) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showThemedToast(
                    this,
                    resources.getString(R.string.multimedia_editor_camera_permission_refused),
                    true
                )
            }

            // We check permissions to set visibility on the camera button, just recreate
            recreateEditingUIUsingCachedRequest()
        }
    }

    private fun cancelActivityWithAssertionFailure(logMessage: String) {
        Timber.e(logMessage)
        showThemedToast(this, getString(R.string.mutimedia_editor_assertion_failed), false)
        finishCancel()
    }

    fun handleFieldChanged(newField: IField) {
        recreateEditingUi(ChangeUIRequest.fieldChange(newField))
    }

    private fun showLargeFileCropDialog(length: Float) {
        val imageFieldController = fieldController as BasicImageFieldController?
        val decimalFormat = DecimalFormat(".00")
        val size = decimalFormat.format(length.toDouble())
        val content = getString(R.string.save_dialog_content, size)
        imageFieldController!!.showCropDialog(content) { saveAndExit() }
    }

    private fun saveAndExit(ignoreField: Boolean = false) {
        val resultData = Intent().apply {
            putExtra(EXTRA_RESULT_FIELD, if (ignoreField) null else field)
            putExtra(EXTRA_RESULT_FIELD_INDEX, fieldIndex)
        }
        setResult(RESULT_OK, resultData)
        finish()
    }

    override fun onDestroy() {
        if (fieldController != null) {
            fieldController!!.onDestroy()
        }
        super<AnkiActivity>.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // This is used to tell the whole activity to shut down if it is restored from Activity restart.
        // Why? I am not really sure. Perhaps to avoid terrible bugs due to not implementing things correctly?
        outState.putBoolean(BUNDLE_KEY_SHUT_OFF, true)

        // We will give field controllers a chance to save / restore across restarts though.
        // If this bundle is not null, on restore, we should continue across Activity restart.
        if (fieldController != null) {
            val controllerBundle = fieldController!!.saveInstanceState()
            if (controllerBundle != null) {
                outState.putBundle("controllerBundle", fieldController!!.saveInstanceState())
            }
        }
        super.onSaveInstanceState(outState)
    }

    /** Intermediate class to hold state for the onRequestPermissionsResult callback  */
    @KotlinCleanup("change constants to enum")
    private class ChangeUIRequest private constructor(val field: IField, val state: Int) {
        var requiresPermissionCheck = true
            private set

        fun markAsPermissionRequested() {
            requiresPermissionCheck = false
        }

        companion object {
            /** Initial request when activity is created  */
            const val ACTIVITY_LOAD = 0

            /** A change in UI via the menu options. Cancellable  */
            const val UI_CHANGE = 1

            /** A change in UI via access to the activity. Not (yet) cancellable  */
            const val EXTERNAL_FIELD_CHANGE = 2
            fun init(field: IField): ChangeUIRequest {
                return ChangeUIRequest(field, ACTIVITY_LOAD)
            }

            fun uiChange(field: IField): ChangeUIRequest {
                return ChangeUIRequest(field, UI_CHANGE)
            }

            fun fieldChange(field: IField): ChangeUIRequest {
                return ChangeUIRequest(field, EXTERNAL_FIELD_CHANGE)
            }
        }
    }

    /**
     * Class to contain logic relating to decisions made when recreating a UI.
     * Can later be converted to a non-static class to allow testing of the logic.
     */
    private object UIRecreationHandler {
        /** Raised just before the field controller is replaced  */
        fun onPreFieldControllerReplacement(previousFieldController: IFieldController?) {
            Timber.d("onPreFieldControllerReplacement")
            // on init, we don't need to do anything
            if (previousFieldController == null) {
                return
            }

            // Otherwise, clean up the previous screen.
            previousFieldController.onFocusLost()
        }

        fun onPostUICreation(request: ChangeUIRequest, activity: MultimediaEditFieldActivity) {
            Timber.d("onPostUICreation. State: %d", request.state)
            when (request.state) {
                ChangeUIRequest.UI_CHANGE, ChangeUIRequest.EXTERNAL_FIELD_CHANGE -> activity.invalidateOptionsMenu()
                ChangeUIRequest.ACTIVITY_LOAD -> {}
                else -> Timber.e("onPostUICreation: Unhandled state: %s", request.state)
            }
        }

        fun onRequiredPermissionDenied(request: ChangeUIRequest, activity: MultimediaEditFieldActivity) {
            Timber.d("onRequiredPermissionDenied. State: %d", request.state)
            when (request.state) {
                ChangeUIRequest.ACTIVITY_LOAD -> activity.finishCancel()
                ChangeUIRequest.UI_CHANGE -> return
                ChangeUIRequest.EXTERNAL_FIELD_CHANGE -> activity.recreateEditingUIUsingCachedRequest()
                else -> {
                    Timber.e("onRequiredPermissionDenied: Unhandled state: %s", request.state)
                    activity.finishCancel()
                }
            }
        }
    }

    private fun createControllerForField(field: IField): IFieldController {
        return when (field.type) {
            EFieldType.TEXT -> BasicTextFieldController()
            EFieldType.IMAGE -> BasicImageFieldController()
            EFieldType.AUDIO_RECORDING -> {
                isAudioUIInitialized = true
                setEditorStatus(true)
                audioRecordingController
            }
            EFieldType.MEDIA_CLIP -> BasicMediaClipFieldController()
        }
    }

    companion object {
        const val EXTRA_RESULT_FIELD = "edit.field.result.field"
        const val EXTRA_RESULT_FIELD_INDEX = "edit.field.result.field.index"
        const val EXTRA_MULTIMEDIA_EDIT_FIELD_ACTIVITY = "multim.card.ed.extra"
        private const val BUNDLE_KEY_SHUT_OFF = "key.edit.field.shut.off"
        const val INTENT_IMAGE_URI = "intent_img_uri"
        private const val REQUEST_AUDIO_PERMISSION = 0
        private const val REQUEST_CAMERA_PERMISSION = 1
        const val IMAGE_LIMIT = 1024 * 1024 // 1MB in bytes
        @KotlinCleanup("see if we can make this non-null")
        @VisibleForTesting
        fun getFieldFromIntent(intent: Intent) = intent.extras!!.getSerializableCompat<MultimediaEditFieldActivityExtra>(EXTRA_MULTIMEDIA_EDIT_FIELD_ACTIVITY)
    }
}

typealias MultimediaEditFieldActivityExtra = Triple<Int, IField, IMultimediaEditableNote>
