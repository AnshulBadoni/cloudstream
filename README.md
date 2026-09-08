# PornTrex CloudStream Provider

Production CloudStream 3 provider for PornTrex featuring:
- **Actor / Performer Panel in 2nd Position** on Homepage
- **Actor Profiles & Complete Filmography Browsing**
- **Multiple Video Stream Qualities / Resolutions (480p, 720p HD, 1080p)**
- **Relative Upload Date to Year Calculation** (e.g. `10 years ago` -> `2016`)
- **Clean Title & Description Extraction**

---

## How to Install in CloudStream

### 1. Push this Repository to your GitHub
```bash
git init
git add .
git commit -m "Initial commit of PornTrex CloudStream Provider"
git branch -M main
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
git push -u origin main
```

### 2. Add Repository to CloudStream App
1. Open the **CloudStream** app on Android.
2. Go to **Settings** -> **Extensions**.
3. Tap **Add Repository**.
4. Enter your repository plugins link:
   ```
   https://raw.githubusercontent.com/<YOUR_USERNAME>/<YOUR_REPO>/builds/plugins.json
   ```
5. Tap **PornTrex** in the list and click **Download / Install**.

---

## Local Development & Testing

Run any test command directly on PC without an Android device:

- **Test Video Details & Stream Qualities:**
  ```powershell
  .\gradlew.bat run --args="load first-site https://www.porntrex.com/video/40753/alix-lynx-alixs-party-2"
  ```

- **Test Actor Profile & Video Catalog:**
  ```powershell
  .\gradlew.bat run --args="person first-site https://www.porntrex.com/models/angela-white/"
  ```

- **Test Homepage Actor Panel (2nd Section):**
  ```powershell
  .\gradlew.bat run --args="catalog first-site actors"
  ```

- **Test Stream Links (Multiple Resolutions):**
  ```powershell
  .\gradlew.bat run --args="links first-site page.html"
  ```

- **Run Automated Test Suite:**
  ```powershell
  .\gradlew.bat test
  ```
