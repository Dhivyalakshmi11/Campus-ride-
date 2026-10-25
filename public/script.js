const $ = (selector) => document.querySelector(selector);
const toast = (message) => {
  const el = $("#toast"); el.textContent = message; el.classList.add("show");
  clearTimeout(toast.timer); toast.timer = setTimeout(() => el.classList.remove("show"), 3500);
};
function formData(form) { return new URLSearchParams(new FormData(form)); }
const API_BASE = "https://campus-ride-5o2g.onrender.com";
async function api(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, options);
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : {}; }
  catch { throw new Error(`Server returned an unexpected response (HTTP ${response.status}). Please check the Java server deployment.`); }
  if (!response.ok || data.ok === false) throw new Error(data.message || "Request failed.");
  return data;
}
function post(path, form) {
  return api(path, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" }, body: formData(form) });
}
function showMessage(selector, message, error = false) {
  const el = $(selector); el.textContent = message; el.style.color = error ? "#c24747" : "#16865c";
}
function safe(value) {
  return String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}
function localDateTimeValue(date) {
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}
const now = localDateTimeValue(new Date());
document.querySelectorAll('input[type="datetime-local"]').forEach(input => { input.min = now; input.value = now; });

$("#registerForm").addEventListener("submit", async e => {
  e.preventDefault();
  try { const result = await post("/api/register", e.currentTarget); showMessage("#registerMessage", result.message); toast(result.message); e.currentTarget.reset(); await refreshAll(); }
  catch (err) { showMessage("#registerMessage", err.message, true); }
});
$("#offerForm").addEventListener("submit", async e => {
  e.preventDefault();
  try { const result = await post("/api/offer", e.currentTarget); showMessage("#offerMessage", result.message); toast(result.message); e.currentTarget.reset(); $("#offerForm [name=seats]").value = 3; $("#offerForm [name=departure]").value = localDateTimeValue(new Date()); await refreshAll(); }
  catch (err) { showMessage("#offerMessage", err.message, true); }
});
$("#requestForm").addEventListener("submit", async e => {
  e.preventDefault();
  try { const result = await post("/api/request", e.currentTarget); showMessage("#requestMessage", result.message); toast(result.message); e.currentTarget.reset(); $("#requestForm [name=seats]").value = 1; $("#requestForm [name=departure]").value = localDateTimeValue(new Date()); await refreshAll(); }
  catch (err) { showMessage("#requestMessage", err.message, true); }
});
$("#settingsForm").addEventListener("submit", async e => {
  e.preventDefault();
  try { const result = await post("/api/settings", e.currentTarget); showMessage("#settingsMessage", result.message); toast(result.message); }
  catch (err) { showMessage("#settingsMessage", err.message, true); }
});
$("#refreshRides").addEventListener("click", refreshAll);
$("#refreshBookings").addEventListener("click", refreshAll);

async function refreshAll() {
  try {
    const [rides, bookings] = await Promise.all([api("/api/rides"), api("/api/bookings")]);
    renderRides(rides); renderBookings(bookings); updateStats(rides, bookings);
  } catch (err) {
    $("#rideList").innerHTML = `<div class="empty-state">Cannot connect to Java server. Start CampusRideServer and refresh.</div>`;
    $("#bookingRows").innerHTML = `<tr><td colspan="6" class="empty-state">Cannot connect to Java server.</td></tr>`;
  }
}
function renderRides(rides) {
  const el = $("#rideList");
  if (!rides.length) { el.innerHTML = '<div class="empty-state">No rides yet. Register a driver and publish the first ride.</div>'; return; }
  el.innerHTML = rides.slice().reverse().map(r => {
    const statusClass = r.status === "CANCELLED" ? "cancelled" : r.status === "FULL" ? "full-status" : "";
    return `<article class="ride-card"><div class="ride-card-top"><span class="ride-id">${safe(r.id)}</span><span class="status-pill ${statusClass}">${safe(r.status)}</span></div>
      <div class="ride-route">${safe(r.pickup)} <span>→</span> ${safe(r.destination)}</div>
      <div class="ride-meta"><div><span>Driver</span><strong>${safe(r.driver)}</strong></div><div><span>Departure</span><strong>${safe(r.departure.replace("T"," "))}</strong></div>
      <div><span>Vehicle</span><strong>${safe(r.vehicleType)} · ${safe(r.vehicle)}</strong></div><div><span>Seats available</span><strong>${safe(r.availableSeats)} / ${safe(r.capacity)}</strong></div></div>
      ${r.status !== "CANCELLED" ? `<div style="margin-top:14px"><button class="table-action" data-cancel-ride="${safe(r.id)}" data-driver="${safe(r.driver)}">Cancel as driver</button></div>` : ""}</article>`;
  }).join("");
  el.querySelectorAll("[data-cancel-ride]").forEach(btn => btn.addEventListener("click", async () => {
    const driver = prompt("Enter the registered driver name to confirm cancellation:");
    if (driver === null) return;
    try {
      const body = new URLSearchParams({ rideId: btn.dataset.cancelRide, driver });
      const result = await api("/api/cancel-driver", { method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8"}, body });
      toast(result.message); await refreshAll();
    } catch (err) { toast(err.message); }
  }));
}
function renderBookings(bookings) {
  const el = $("#bookingRows");
  if (!bookings.length) { el.innerHTML = '<tr><td colspan="6" class="empty-state">No requests yet. Try the Find a ride form.</td></tr>'; return; }
  el.innerHTML = bookings.slice().reverse().map(b => {
    const canCancel = ["MATCHED","PENDING"].includes(b.status);
    const canPickup = b.status === "MATCHED";
    return `<tr><td><strong>${safe(b.id)}</strong></td><td>${safe(b.passenger)}</td>
      <td>${safe(b.pickup)} → ${safe(b.destination)}<br><span style="color:#8a97a9">${safe(b.departure.replace("T"," "))}</span></td>
      <td>${safe(b.seats)}</td><td>${safe(b.rideId || "—")}</td>
      <td><span class="status-pill ${b.status === "CANCELLED" ? "cancelled" : ""}">${safe(b.status)}</span>
      ${canPickup ? `<button class="table-action" data-booking="${safe(b.id)}" data-status="PICKED_UP">Picked up</button>` : ""}
      ${canCancel ? `<button class="table-action" data-booking="${safe(b.id)}" data-status="CANCELLED">Cancel</button>` : ""}
      ${b.status === "PICKED_UP" ? `<button class="table-action" data-booking="${safe(b.id)}" data-status="COMPLETED">Complete</button>` : ""}
      ${b.message ? `<div style="font-size:10px;margin-top:5px">${safe(b.message)}</div>` : ""}</td></tr>`;
  }).join("");
  el.querySelectorAll("[data-booking]").forEach(btn => btn.addEventListener("click", async () => {
    try {
      const body = new URLSearchParams({ bookingId: btn.dataset.booking, status: btn.dataset.status });
      const result = await api("/api/booking-status", { method: "POST", headers: {"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8"}, body });
      toast(result.message); await refreshAll();
    } catch (err) { toast(err.message); }
  }));
}
function updateStats(rides, bookings) {
  $("#statRides").textContent = rides.filter(r => r.status !== "CANCELLED").length;
  $("#statSeats").textContent = rides.filter(r => r.status !== "CANCELLED").reduce((sum, r) => sum + r.availableSeats, 0);
  $("#statMatched").textContent = bookings.filter(b => ["MATCHED","PICKED_UP","COMPLETED"].includes(b.status)).length;
  $("#statPending").textContent = bookings.filter(b => b.status === "PENDING").length;
}
refreshAll();
