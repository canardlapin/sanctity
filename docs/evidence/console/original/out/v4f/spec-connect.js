window.SPEC = {"hash": "#/"};
try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}
location.hash = window.SPEC.hash;
