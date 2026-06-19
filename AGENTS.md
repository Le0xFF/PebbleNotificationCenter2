# Pebble Notification Center project

* This folder contains an implementation of a different notification center for Pebble smartwatches.

Two different kind of applications are available:

* The one for Pebble smartwatch itself, which can be found in `watch` directory.
* The one for Android smartphone, which can be found in `mobile` directory. This application is called "companion app" and is needed for the Pebble smartwatch application to correctly receive notification from the smartphone.

## Additional project details

* Additional information on how it works can be found in `README.MD` file.
* Additional information on how the protocol between the smartwatch and the smartphone works can be found in `protocol.md` file.
* Additional implementation details that has been done can be found in: `IMPLEMENTATION_SUMMARY.md`
* Compilation instructions can be found in: `COMPILE_INSTRUCTION.md`
  * Android SDK path is: `"${HOME}/ANDROID/sdk"`
  * Export it to `ANDROID_HOME` variable is needed during companion app compilation.
* Folder `PebbleCommons` contains additional function used by the Notification project.
* Folder `EXAMPLE_SCREENSHOTS`, when it's not empty, contains images relative to certain features that must be changed or checked.
  * Additional details regarding each image can be found in: `EXAMPLE_SCREENSHOTS/SCREENSHOTS_DETAILS.md`.
  * When the folder is empty, it must be ignored.

## Additional details about Pebble

The complete explanation about Pebble platform and its SDK can be found in: `${HOME}/PEBBLE/AGENTS.md`
