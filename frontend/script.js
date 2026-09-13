const toast = document.querySelector('.toast');
const tabs = document.querySelectorAll('.tab');
tabs.forEach((tab) => tab.addEventListener('click', () => {
  tabs.forEach((item) => item.classList.remove('active'));
  document.querySelectorAll('.tab-panel').forEach((panel) => panel.classList.remove('active'));
  tab.classList.add('active');
  document.querySelector(`#panel-${tab.dataset.tab}`).classList.add('active');
}));

document.querySelectorAll('[data-copy]').forEach((button) => button.addEventListener('click', async () => {
  const value = button.dataset.copy;
  try { await navigator.clipboard.writeText(value); } catch { window.prompt('复制命令：', value); }
  toast.classList.add('show');
  window.setTimeout(() => toast.classList.remove('show'), 1600);
}));
