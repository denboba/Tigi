"""Write google-services.json for the Tigi package.

If the GOOGLE_SERVICES_JSON env var holds a real Firebase config it is used as is.
Otherwise a clearly fake placeholder is written so the Gradle google-services plugin
passes. With the placeholder, push notifications (FCM) will not work.
"""
import json, os, sys

PACKAGE = "com.tigi.app"
targets = sys.argv[1:]
real = os.environ.get("GOOGLE_SERVICES_JSON", "").strip()

if real:
    data = json.loads(real)
    names = [c["client_info"]["android_client_info"]["package_name"] for c in data["client"]]
    if PACKAGE not in names:
        sys.exit(f"GOOGLE_SERVICES_JSON has no client for {PACKAGE}: {names}")
else:
    data = {
        "project_info": {
            "project_number": "000000000000",
            "project_id": "tigi-placeholder",
            "storage_bucket": "tigi-placeholder.appspot.com",
        },
        "client": [{
            "client_info": {
                "mobilesdk_app_id": "1:000000000000:android:0000000000000000",
                "android_client_info": {"package_name": PACKAGE},
            },
            "oauth_client": [],
            "api_key": [{"current_key": "PLACEHOLDER_NOT_A_REAL_KEY"}],
            "services": {"appinvite_service": {"other_platform_oauth_client": []}},
        }],
        "configuration_version": "1",
    }

for t in targets:
    with open(t, "w") as f:
        json.dump(data, f, indent=2)
    print("wrote", t, "(real)" if real else "(placeholder)")
