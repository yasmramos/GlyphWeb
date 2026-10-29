# GlyphWeb

Lightweight WebKit bindings for Java 17+, powering the **Glyph UI** declarative framework.

## Modules
| Module | Purpose |
|---|---|
| `glyph-native` | INTERNAL JavaCPP/JNI binding surface (`io.glyph.nativeapi.webkit`) — raw pointers only. |
| `glyph-web-core` | Safe lifecycle management: `GlyphWebView`, `WebKitRuntime`, `WebKitLifecycleManager`. |

## Quick start (M1)
```java
WebKitRuntime.getInstance().initialize();          // probe -> bind -> gtk_init_check on UI thread
try (GlyphWebView view = GlyphWebView.create()) {  // NEW -> CREATED, registered in CHM registry
    view.attachToParent(hostWindow);               // CREATED -> ATTACHED
    view.makeVisible();                             // ATTACHED -> VISIBLE
    view.loadUri("https://glyph.dev");
}                                                    // deterministic DESTROYING -> DESTROYED
```

Build: `mvn -B test` (pure-JVM unit tests run without libwebkit; native paths guarded by `NativeWebKit.ensureLoaded()`).

Architecture spec: [`docs/architecture/glyphweb-lifecycle-v1.json`](docs/architecture/glyphweb-lifecycle-v1.json)
