const apiBase = '/api';
let menu = [];
let cart = {};

async function fetchMenu() {
  const r = await fetch(apiBase + '/menu');
  menu = await r.json();
  renderMenu();
}

function renderMenu() {
  const container = document.getElementById('menu');
  container.innerHTML = '';
  menu.forEach(item => {
    const card = document.createElement('div');
    card.className = 'card';
    card.innerHTML = `
      <h3>${escapeHtml(item.name)}</h3>
      <p>${escapeHtml(item.description)}</p>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <div class="price">₹${item.price.toFixed(2)}</div>
        <button class="btn" data-id="${item.id}">Add</button>
      </div>`;
    container.appendChild(card);
  });
  container.querySelectorAll('.btn').forEach(btn => {
    btn.onclick = () => addToCart(btn.dataset.id);
  });
}

function escapeHtml(s) { return s ? s.replace(/[&<>"']/g, c=>({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c])) : ''; }

function addToCart(id) {
  const it = menu.find(m => String(m.id) === String(id));
  if (!it) return;
  cart[id] = (cart[id] || { ...it, qty: 0 });
  cart[id].qty += 1;
  renderCart();
}

function renderCart() {
  const el = document.getElementById('cart');
  el.innerHTML = '';
  let total = 0;
  Object.values(cart).forEach(ci => {
    total += ci.price * ci.qty;
    const node = document.createElement('div');
    node.className = 'cart-item';
    node.innerHTML = `<div>${escapeHtml(ci.name)} x ${ci.qty}</div><div>₹${(ci.price*ci.qty).toFixed(2)}</div>`;
    el.appendChild(node);
  });
  const totalNode = document.createElement('div');
  totalNode.style.marginTop = '8px';
  totalNode.innerHTML = `<strong>Total: ₹${total.toFixed(2)}</strong>`;
  el.appendChild(totalNode);
}

document.getElementById('checkout').onclick = async () => {
  const name = document.getElementById('name').value.trim();
  const phone = document.getElementById('phone').value.trim();
  if (!name || !phone) return alert('Enter name and phone');
  const items = Object.values(cart).map(ci => ({ menuItemId: ci.id, quantity: ci.qty }));
  const body = { customerName: name, customerPhone: phone, items };
  const r = await fetch(apiBase + '/orders', { method: 'POST', headers: {'content-type':'application/json'}, body: JSON.stringify(body) });
  const data = await r.json();
  if (!r.ok) {
    alert('Error: ' + (data.error || 'unknown'));
    return;
  }
  document.getElementById('orderResult').innerText = `Order placed: ${data.orderId} (status: ${data.status}). PaymentToken: ${data.paymentToken}`;
  cart = {};
  renderCart();
};

document.getElementById('trackBtn').onclick = () => {
  const id = document.getElementById('orderIdInput').value.trim();
  if (!id) return alert('Enter order id');
  const events = document.getElementById('events');
  events.innerHTML = 'Connecting...';
  const es = new EventSource(apiBase + '/orders/' + encodeURIComponent(id) + '/events');
  es.addEventListener('status', e => {
    const data = JSON.parse(e.data);
    events.innerHTML = `<div><strong>Status:</strong> ${data.status} <em>${data.timestamp}</em></div>` + events.innerHTML;
  });
  es.addEventListener('notification', e => {
    events.innerHTML = `<div style="color:green">${e.data}</div>` + events.innerHTML;
  });
  es.onerror = () => { events.innerHTML += '<div style="color:red">Connection closed.</div>'; es.close(); };
};

fetchMenu();
