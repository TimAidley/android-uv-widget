# The widget provider and the config activity are referenced only from the manifest,
# and the worker only by class name from WorkManager's database.
-keep class com.aidley.uvwidget.UvWidgetProvider { *; }
-keep class com.aidley.uvwidget.ConfigActivity { *; }
-keep class com.aidley.uvwidget.UvRefreshWorker { *; }
