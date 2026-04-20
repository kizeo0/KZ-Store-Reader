# Keep Apache Commons Net (FTP)
-keep class org.apache.commons.net.** { *; }

# Keep PS3 PKG extractor
-keep class com.kizeo.kzstorereader.utils.PkgExtractor { *; }
-keep class com.kizeo.kzstorereader.utils.StoreXmlParser { *; }

# Keep XML DOM classes
-keep class org.w3c.** { *; }
-keep class javax.xml.** { *; }
-dontwarn org.apache.commons.**
