object IosPickerBridge {
    fun install(filePicker: IosFilePicker, directoryPicker: IosDirectoryPicker) {
        this.filePicker = filePicker
        this.directoryPicker = directoryPicker
        if (started) return
        started = true
        scope.launch {
            filePicker.requests.collect { req ->
                val handler = onPickFiles
                if (handler == null) {
                    Napier.w("IosPickerBridge: file pick requested but no Swift handler registered")
                    filePicker.completeRequest(emptyList())
                } else {
                    handler(req.allowMultiple)
                }
            }
        }
        scope.launch {
            directoryPicker.requests.collect {
                val handler = onPickDirectory
                if (handler == null) {
                    Napier.w("IosPickerBridge: directory pick requested but no Swift handler registered")
                    directoryPicker.completeRequest(null)
                } else {
                    handler()
                }
            }
        }
        Napier.i("IosPickerBridge installed (file + directory request collectors live)")
    }

    /** Swift -> Kotlin: the user finished picking files (empty list on cancel). */
    fun completeFiles(files: List<PlatformFile>) {
        filePicker?.completeRequest(files)
    }

    /** Swift -> Kotlin: the user finished picking a folder (null on cancel). */
    fun completeDirectory(locator: String?) {
        directoryPicker?.completeRequest(locator)
    }
}