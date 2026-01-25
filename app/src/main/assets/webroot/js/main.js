import AppController from './controllers/app-controller.js';

// Bootstrap the app (ES module entrypoint).
window.addEventListener('DOMContentLoaded', () => {
    new AppController();
});
