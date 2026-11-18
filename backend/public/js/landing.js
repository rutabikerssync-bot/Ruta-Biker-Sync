/**
 * RUTA BIKER SYNC — Lógica Interactiva de la Landing Page
 * Incluye:
 * 1. Simulador de Malla Bluetooth Off-Grid en Canvas
 * 2. Calculadora de Club & Suscripción en Tiempo Real
 * 3. Acordeón interactivo de Preguntas Frecuentes (FAQ)
 * 4. Animaciones de entrada al hacer scroll
 */

document.addEventListener('DOMContentLoaded', () => {
  initMeshSimulator();
  initPricingCalculator();
  initFaqAccordion();
});

/* ==========================================================================
   1. SIMULADOR DE MALLA BLE EN TIEMPO REAL (CANVAS INTERACTIVO)
   ========================================================================== */
function initMeshSimulator() {
  const canvas = document.getElementById('meshCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');

  function resize() {
    canvas.width = canvas.parentElement.clientWidth;
    canvas.height = 320;
  }
  resize();
  window.addEventListener('resize', resize);

  // Nodos moteros de la rodada
  const nodes = [
    { id: 'Líder (Águila)', x: 0.85, y: 0.35, vx: 0.0003, vy: 0.0002, color: '#e5b869', role: 'LÍDER' },
    { id: 'Capitán de Ruta', x: 0.65, y: 0.45, vx: -0.0004, vy: 0.0003, color: '#10b981', role: 'CAPITÁN' },
    { id: 'Sargento de Armas', x: 0.45, y: 0.40, vx: 0.0005, vy: -0.0004, color: '#38bdf8', role: 'SARGENTO' },
    { id: 'Miembro Escolta', x: 0.30, y: 0.60, vx: -0.0003, vy: -0.0003, color: '#38bdf8', role: 'MIEMBRO' },
    { id: 'Barredora (Cola)', x: 0.15, y: 0.50, vx: 0.0004, vy: 0.0004, color: '#f59e0b', role: 'BARREDORA' }
  ];

  let packets = [];
  const MAX_CONNECT_DIST = 260; // Distancia máxima BLE simulada

  function emitPacket(fromNode, toNode) {
    packets.push({
      x: fromNode.x * canvas.width,
      y: fromNode.y * canvas.height,
      targetX: toNode.x * canvas.width,
      targetY: toNode.y * canvas.height,
      progress: 0,
      speed: 0.035
    });
  }

  // Enviar periódicamente telemetría y clips de voz por la malla
  setInterval(() => {
    if (nodes.length >= 2) {
      const idx = Math.floor(Math.random() * (nodes.length - 1));
      emitPacket(nodes[idx], nodes[idx + 1]);
    }
  }, 900);

  function animate() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Dibujar carretera simulada con curvas
    ctx.beginPath();
    ctx.moveTo(0, canvas.height * 0.65);
    ctx.bezierCurveTo(
      canvas.width * 0.35, canvas.height * 0.8,
      canvas.width * 0.65, canvas.height * 0.2,
      canvas.width, canvas.height * 0.35
    );
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.06)';
    ctx.lineWidth = 36;
    ctx.lineCap = 'round';
    ctx.stroke();

    // Actualizar posiciones sutiles
    nodes.forEach(n => {
      n.x += n.vx;
      n.y += n.vy;
      if (n.x < 0.1 || n.x > 0.9) n.vx *= -1;
      if (n.y < 0.2 || n.y > 0.8) n.vy *= -1;
    });

    // Dibujar enlaces BLE entre motos cercanas
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const dx = (nodes[i].x - nodes[j].x) * canvas.width;
        const dy = (nodes[i].y - nodes[j].y) * canvas.height;
        const dist = Math.sqrt(dx * dx + dy * dy);

        if (dist < MAX_CONNECT_DIST) {
          const alpha = 1 - dist / MAX_CONNECT_DIST;
          ctx.beginPath();
          ctx.moveTo(nodes[i].x * canvas.width, nodes[i].y * canvas.height);
          ctx.lineTo(nodes[j].x * canvas.width, nodes[j].y * canvas.height);
          ctx.strokeStyle = `rgba(16, 185, 129, ${alpha * 0.55})`;
          ctx.lineWidth = 2;
          ctx.setLineDash([4, 4]);
          ctx.stroke();
          ctx.setLineDash([]);
        }
      }
    }

    // Dibujar paquetes de datos BLE en tránsito
    packets = packets.filter(p => {
      p.progress += p.speed;
      const curX = p.x + (p.targetX - p.x) * p.progress;
      const curY = p.y + (p.targetY - p.y) * p.progress;

      ctx.beginPath();
      ctx.arc(curX, curY, 4, 0, Math.PI * 2);
      ctx.fillStyle = '#34d399';
      ctx.shadowColor = '#10b981';
      ctx.shadowBlur = 8;
      ctx.fill();
      ctx.shadowBlur = 0;

      return p.progress < 1;
    });

    // Dibujar nodos de las motos
    nodes.forEach(n => {
      const px = n.x * canvas.width;
      const py = n.y * canvas.height;

      // Radio de alcance BLE transparente
      ctx.beginPath();
      ctx.arc(px, py, 45, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(200, 109, 59, 0.05)';
      ctx.fill();

      // Círculo del piloto
      ctx.beginPath();
      ctx.arc(px, py, 11, 0, Math.PI * 2);
      ctx.fillStyle = n.color;
      ctx.shadowColor = n.color;
      ctx.shadowBlur = 10;
      ctx.fill();
      ctx.shadowBlur = 0;

      // Borde
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 2;
      ctx.stroke();

      // Etiqueta del rol
      ctx.font = '600 11px Outfit, sans-serif';
      ctx.fillStyle = '#ffffff';
      ctx.textAlign = 'center';
      ctx.fillText(n.role, px, py - 16);

      // Icono
      ctx.font = '10px Inter, sans-serif';
      ctx.fillStyle = 'rgba(255, 255, 255, 0.7)';
      ctx.fillText(n.id, px, py + 24);
    });

    requestAnimationFrame(animate);
  }

  animate();

  // Botón para simular caída SOS en la malla
  const btnSosTest = document.getElementById('btnSimulateSos');
  if (btnSosTest) {
    btnSosTest.addEventListener('click', () => {
      const tail = nodes[nodes.length - 1];
      tail.color = '#ef4444';
      tail.role = '🚨 SOS CAÍDA';
      // Ráfaga de paquetes hacia el líder
      for (let i = nodes.length - 1; i > 0; i--) {
        setTimeout(() => {
          emitPacket(nodes[i], nodes[i - 1]);
        }, (nodes.length - 1 - i) * 150);
      }
      setTimeout(() => {
        tail.color = '#f59e0b';
        tail.role = 'BARREDORA';
      }, 4000);
    });
  }
}

/* ==========================================================================
   2. CALCULADORA DE COSTO DE CLUB (BUSINESS MODEL OFICIAL)
   ========================================================================== */
function initPricingCalculator() {
  const membersSlider = document.getElementById('membersRange');
  const leadersSlider = document.getElementById('leadersRange');
  const membersCountEl = document.getElementById('membersCount');
  const leadersCountEl = document.getElementById('leadersCount');
  
  const leadersSubtotalEl = document.getElementById('leadersSubtotal');
  const membersSubtotalEl = document.getElementById('membersSubtotal');
  const grandTotalEl = document.getElementById('grandTotal');
  const costPerRiderEl = document.getElementById('costPerRider');

  if (!membersSlider || !leadersSlider) return;

  function update() {
    const members = parseInt(membersSlider.value, 10);
    const leaders = parseInt(leadersSlider.value, 10);

    const leadersCost = leaders * 15.0; // $15/mes por líder
    const membersCost = members * 2.50; // $2.50/mes por miembro
    const total = leadersCost + membersCost;
    const totalPeople = leaders + members;
    const perRider = totalPeople > 0 ? (total / totalPeople).toFixed(2) : '0.00';

    if (membersCountEl) membersCountEl.textContent = `${members} pilotos`;
    if (leadersCountEl) leadersCountEl.textContent = `${leaders} líder${leaders > 1 ? 'es' : ''}`;
    
    if (leadersSubtotalEl) leadersSubtotalEl.textContent = `$${leadersCost.toFixed(2)}/mes`;
    if (membersSubtotalEl) membersSubtotalEl.textContent = `$${membersCost.toFixed(2)}/mes`;
    if (grandTotalEl) grandTotalEl.textContent = `$${total.toFixed(2)} USD`;
    if (costPerRiderEl) costPerRiderEl.textContent = `~$${perRider} / mes por piloto`;
  }

  membersSlider.addEventListener('input', update);
  leadersSlider.addEventListener('input', update);
  update();
}

/* ==========================================================================
   3. ACORDEÓN DE PREGUNTAS FRECUENTES (FAQ)
   ========================================================================== */
function initFaqAccordion() {
  const faqItems = document.querySelectorAll('.faq-item');
  faqItems.forEach(item => {
    const question = item.querySelector('.faq-question');
    if (question) {
      question.addEventListener('click', () => {
        const isActive = item.classList.contains('active');
        // Cerrar otros
        faqItems.forEach(other => other.classList.remove('active'));
        if (!isActive) {
          item.classList.add('active');
        }
      });
    }
  });
}
