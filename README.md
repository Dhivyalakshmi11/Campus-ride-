# CampusRide

CampusRide is a student ride-sharing mini project using HTML, CSS, JavaScript, and a Java HTTP server.

## Deployment layout
- `index.html`, `style.css`, `script.js`: frontend files served by Vercel from the repository root.
- `public/`: same frontend files for the Java server's static-file hosting.
- `CampusRideServer.java`: Java API server.
- `Dockerfile`: Render Docker deployment configuration.

The frontend currently calls `https://campus-ride-5o2g.onrender.com` for API requests. If the Render service URL changes, update `API_BASE` in both `script.js` and `public/script.js`.

## Render
Use Docker as the runtime/build environment. Render reads the Dockerfile, compiles the Java server, and runs it on Render's assigned `PORT`.

## Vercel
Deploy the repository root as a static site. The root `index.html`, `style.css`, and `script.js` are intentionally included so Vercel serves the correct files.
