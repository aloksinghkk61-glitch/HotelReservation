# The Larkspur Hotel: reservation website

Java back end (JDK built-in HTTP server, no frameworks) plus a single-page web UI.
Search rooms, book with a simulated card payment, view a booking, cancel with a refund.

```
HotelSystem.java   domain model, payment simulation, CSV file storage, business logic (also a CLI)
HotelWeb.java      HTTP server + JSON API
public/index.html  the website
Dockerfile         for deployment
```

## Run locally

Needs JDK 17 or newer.

```bash
javac -d out *.java
java -cp out HotelWeb          # then open http://localhost:8080
```

Environment variables: `PORT` (default 8080), `DATA_DIR` (default `./hotel_data`), `PUBLIC_DIR` (default `./public`).

The original terminal version still works: `java -cp out HotelSystem`.

## Deploy

The app is one Docker container. Any host that runs Docker images works.

### Render (easiest)
1. Push this folder to a GitHub repository.
2. On render.com choose New > Web Service, connect the repo, and set Runtime to **Docker**.
3. Deploy. Health check path: `/health`.
4. Bookings are stored in `/data`. On Render's free plan the disk resets on every deploy or restart,
   so bookings are lost. To keep them, use a paid plan, add a Disk mounted at `/data`.

### Railway
New Project > Deploy from GitHub repo. Railway detects the Dockerfile and sets `PORT` for you.
Add a Volume mounted at `/data` to keep bookings.

### Fly.io
```bash
fly launch            # accepts the Dockerfile; internal port 8080
fly volumes create hotel_data --size 1
# in fly.toml add:  [mounts]  source = "hotel_data"  destination = "/data"
fly deploy
```

### Any VPS
```bash
docker build -t larkspur .
docker run -d -p 80:8080 -v larkspur_data:/data larkspur
```

## Before you take real traffic
- Payment is a simulation. Never collect real card numbers with this code.
- A booking can be viewed or cancelled only with its ID and the guest's email. There are no staff accounts.
- Add HTTPS (Render, Railway and Fly provide it automatically) and consider rate limiting at your host or proxy.
- CSV storage suits a demo or a very small hotel. For heavier use, replace `FileStore` with a database
  (the rest of the code only talks to `FileStore` through `HotelService`).
