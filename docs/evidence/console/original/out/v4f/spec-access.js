window.SPEC = {"hash": "#/d/b7e3c1d2-5a4f-4e8b-9c21-6f0a3d9e8b14/access?release=9d4b2e61-7a3c-4f18-a5d9-0e6c1b2f8a73", "steps": [{"wait": "#g-recipient"}, {"fill": ["#g-recipient", "6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64"]}, {"check": ".action:nth-child(2) input"}, {"click": "form button[type=submit]"}, {"wait": "table.data tbody tr"}]};
try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}
location.hash = window.SPEC.hash;
