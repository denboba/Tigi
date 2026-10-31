"""Write google-services.json for the Tigi package.

If the GOOGLE_SERVICES_JSON env var holds a real Firebase config it is used as is.
Otherwise a clearly fake placeholder is written so the Gradle google-services plugin
passes. With the placeholder, push notifications (FCM) will not work.
"""
import json, os, sys

def app_package():
    # the package the app is built with (APP_PACKAGE in gradle.properties)
    try:
        with open("gradle.properties") as f:
            for line in f:
                if line.strip().startswith("APP_PACKAGE="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return "com.nilak.app"

PACKAGE = app_package()
targets = sys.argv[1:]
real = os.environ.get("GOOGLE_SERVICES_JSON", "").strip()

data = None
if real:
    data = json.loads(real)
    names = [c["client_info"]["android_client_info"]["package_name"] for c in data["client"]]
    if PACKAGE not in names:
        # e.g. the Firebase app is already com.nilak.app while the build is still the old package:
        # build with the placeholder until the package matches, instead of failing every build
        print(f"::warning title=Firebase config waits for the package::GOOGLE_SERVICES_JSON is for {names}, the app is {PACKAGE}; using the placeholder (no background push) until they match.")
        data = None
        real = ""
if data is None:
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

# The TMessagesProj library module keeps the namespace org.telegram.messenger, and the
# google-services plugin looks that name up too, so every file lists both packages.
import copy
LIB_PACKAGE = "org.telegram.messenger"
have = [c["client_info"]["android_client_info"]["package_name"] for c in data["client"]]
if LIB_PACKAGE not in have:
    extra = copy.deepcopy(data["client"][0])
    extra["client_info"]["android_client_info"]["package_name"] = LIB_PACKAGE
    data["client"].append(extra)

for t in targets:
    with open(t, "w") as f:
        json.dump(data, f, indent=2)
    print("wrote", t, "(real)" if real else "(placeholder)")
