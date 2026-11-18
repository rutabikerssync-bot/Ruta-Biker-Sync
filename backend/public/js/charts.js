/**
 * Ruta Biker Sync — Motor Ligero de Gráficas Canvas
 * Diseñado con estética Clean Modern Light SaaS.
 */

window.BikerCharts = class BikerCharts {
  /**
   * Renderiza una gráfica de área/línea interactiva para actividad semanal de rodadas
   */
  static renderRidesActivityChart(canvasId, data = [14, 22, 18, 35, 48, 86, 92]) {
    try {
      const canvas = document.getElementById(canvasId);
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();

      const width = Math.max(rect.width || canvas.clientWidth || 300, 200);
      const height = Math.max(rect.height || canvas.clientHeight || 200, 150);

      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);

      const padding = { top: 25, right: 20, bottom: 35, left: 40 };
      const usableWidth = Math.max(20, width - padding.left - padding.right);
      const usableHeight = Math.max(20, height - padding.top - padding.bottom);

      ctx.clearRect(0, 0, width, height);

      const labels = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
      const maxVal = Math.max(...data, 100);

      // Eje X y cuadrícula horizontal en tono claro nítido
      ctx.strokeStyle = '#E2E8F0';
      ctx.lineWidth = 1;
      ctx.fillStyle = '#64748B';
      ctx.font = '11px Outfit, Inter, sans-serif';

      const gridLines = 4;
      for (let i = 0; i <= gridLines; i++) {
        const y = padding.top + (usableHeight / gridLines) * i;
        const val = Math.round(maxVal - (maxVal / gridLines) * i);
        ctx.beginPath();
        ctx.moveTo(padding.left, y);
        ctx.lineTo(width - padding.right, y);
        ctx.stroke();
        ctx.fillText(`${val}`, 8, y + 3);
      }

      // Puntos de datos
      const stepX = usableWidth / Math.max(1, data.length - 1);
      const points = data.map((val, idx) => {
        const x = padding.left + idx * stepX;
        const y = height - padding.bottom - (val / maxVal) * usableHeight;
        return { x, y, val, label: labels[idx] };
      });

      // Relleno de Área con Gradiente Índigo Suave
      const gradient = ctx.createLinearGradient(0, padding.top, 0, height - padding.bottom);
      gradient.addColorStop(0, 'rgba(79, 70, 229, 0.25)');
      gradient.addColorStop(0.7, 'rgba(79, 70, 229, 0.05)');
      gradient.addColorStop(1, 'rgba(79, 70, 229, 0.0)');

      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (let i = 1; i < points.length; i++) {
        const xc = (points[i].x + points[i - 1].x) / 2;
        const yc = (points[i].y + points[i - 1].y) / 2;
        ctx.quadraticCurveTo(points[i - 1].x, points[i - 1].y, xc, yc);
      }
      ctx.lineTo(points[points.length - 1].x, points[points.length - 1].y);
      ctx.lineTo(points[points.length - 1].x, height - padding.bottom);
      ctx.lineTo(points[0].x, height - padding.bottom);
      ctx.closePath();
      ctx.fillStyle = gradient;
      ctx.fill();

      // Línea principal Índigo Cobalto
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (let i = 1; i < points.length; i++) {
        const xc = (points[i].x + points[i - 1].x) / 2;
        const yc = (points[i].y + points[i - 1].y) / 2;
        ctx.quadraticCurveTo(points[i - 1].x, points[i - 1].y, xc, yc);
      }
      ctx.lineTo(points[points.length - 1].x, points[points.length - 1].y);
      ctx.strokeStyle = '#4F46E5';
      ctx.lineWidth = 2.5;
      ctx.stroke();

      // Dibujar puntos con borde blanco y centro índigo
      points.forEach((p) => {
        ctx.beginPath();
        ctx.arc(p.x, p.y, 4.5, 0, Math.PI * 2);
        ctx.fillStyle = '#FFFFFF';
        ctx.fill();
        ctx.strokeStyle = '#4F46E5';
        ctx.lineWidth = 2.5;
        ctx.stroke();

        ctx.fillStyle = '#64748B';
        ctx.fillText(p.label, p.x - 10, height - 12);
      });
    } catch (err) {
      console.warn('[BikerCharts] Error renderizando rides chart:', err);
    }
  }

  /**
   * Renderiza gráfica Donut de distribución de Rangos en modo claro
   */
  static renderRanksDonutChart(canvasId, ranks = { novato: 30, rider: 45, veterano: 20, elite: 8, leyenda: 3 }) {
    try {
      const canvas = document.getElementById(canvasId);
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();

      const width = Math.max(rect.width || canvas.clientWidth || 300, 200);
      const height = Math.max(rect.height || canvas.clientHeight || 200, 150);

      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, width, height);

      const centerX = width * 0.38;
      const centerY = height * 0.5;
      const outerRadius = Math.max(25, Math.min(centerX, centerY) - 15);
      const innerRadius = Math.max(12, outerRadius * 0.65);

      const segments = [
        { label: 'Novato', val: ranks?.novato ?? 30, color: '#94A3B8' },
        { label: 'Rider', val: ranks?.rider ?? 45, color: '#0284C7' },
        { label: 'Veterano', val: ranks?.veterano ?? 20, color: '#059669' },
        { label: 'Élite', val: ranks?.elite ?? 8, color: '#D97706' },
        { label: 'Leyenda', val: ranks?.leyenda ?? 3, color: '#4F46E5' }
      ];

      const total = segments.reduce((sum, s) => sum + s.val, 0) || 1;
      let startAngle = -Math.PI / 2;

      segments.forEach((seg) => {
        const sliceAngle = (seg.val / total) * Math.PI * 2;
        const endAngle = startAngle + sliceAngle;

        ctx.beginPath();
        ctx.arc(centerX, centerY, outerRadius, startAngle, endAngle);
        ctx.arc(centerX, centerY, innerRadius, endAngle, startAngle, true);
        ctx.closePath();
        ctx.fillStyle = seg.color;
        ctx.fill();

        startAngle = endAngle;
      });

      // Centro del donut: Total en texto oscuro nítido
      ctx.fillStyle = '#0F172A';
      ctx.font = 'bold 17px Outfit, Inter, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(`${total}`, centerX, centerY - 6);
      ctx.fillStyle = '#64748B';
      ctx.font = '10px Outfit, Inter, sans-serif';
      ctx.fillText('RIDERS', centerX, centerY + 12);

      // Leyenda a la derecha
      const legendX = width * 0.68;
      let legendY = 30;
      ctx.textAlign = 'left';

      segments.forEach((seg) => {
        ctx.fillStyle = seg.color;
        ctx.beginPath();
        ctx.arc(legendX + 5, legendY + 5, 5, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = '#1E293B';
        ctx.font = '11px Outfit, Inter, sans-serif';
        const pct = Math.round((seg.val / total) * 100);
        ctx.fillText(`${seg.label} (${pct}%)`, legendX + 16, legendY + 9);

        legendY += 24;
      });
    } catch (err) {
      console.warn('[BikerCharts] Error renderizando ranks donut:', err);
    }
  }

  /**
   * Renderiza gráfica de barras horizontales de Efectividad SOS (Confirmadas vs Falsas Alarmas)
   */
  static renderSosMetricsChart(canvasId, sosData = { confirmed: 4, cancelled: 11 }) {
    try {
      const canvas = document.getElementById(canvasId);
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      const rect = canvas.getBoundingClientRect();

      const width = Math.max(rect.width || canvas.clientWidth || 300, 200);
      const height = Math.max(rect.height || canvas.clientHeight || 180, 140);

      canvas.width = width * dpr;
      canvas.height = height * dpr;
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, width, height);

      const confirmed = sosData?.confirmed ?? 4;
      const cancelled = sosData?.cancelled ?? 11;
      const total = (confirmed + cancelled) || 1;
      const cancelledPct = Math.round((cancelled / total) * 100);
      const confirmedPct = Math.round((confirmed / total) * 100);

      const barWidth = Math.max(50, width - 30);

      ctx.font = '12px Outfit, Inter, sans-serif';

      // Barra 1: Canceladas en 20 s (Filtro Inteligente)
      ctx.fillStyle = '#059669';
      ctx.fillText(`Descartadas en 20 s (Canceladas a tiempo): ${cancelled} (${cancelledPct}%)`, 15, 30);

      ctx.fillStyle = '#F1F5F9';
      ctx.fillRect(15, 40, barWidth, 22);
      ctx.fillStyle = '#059669';
      ctx.fillRect(15, 40, barWidth * (cancelledPct / 100), 22);

      // Barra 2: Emergencias Reales SOS
      ctx.fillStyle = '#DC2626';
      ctx.fillText(`Emergencias Reales Atendidas: ${confirmed} (${confirmedPct}%)`, 15, 95);

      ctx.fillStyle = '#F1F5F9';
      ctx.fillRect(15, 105, barWidth, 22);
      ctx.fillStyle = '#DC2626';
      ctx.fillRect(15, 105, barWidth * (confirmedPct / 100), 22);

      // Resumen de efectividad
      ctx.fillStyle = '#64748B';
      ctx.font = '11px Outfit, Inter, sans-serif';
      ctx.fillText(`⚡ Precisión del algoritmo de caída: ${cancelledPct}% de falsas alarmas filtradas sin molestar al convoy.`, 15, 155);
    } catch (err) {
      console.warn('[BikerCharts] Error renderizando sos chart:', err);
    }
  }
};
