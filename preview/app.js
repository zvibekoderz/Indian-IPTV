/* Interactive UI preview for the `experiment` branch of Indian IPTV.
   Screens mirror the real Compose routes in app/src/main/java/com/indian/iptv/MainActivity.kt */

const $screen = document.getElementById('screen');
const $toast = document.getElementById('toast');
const $tabs = document.getElementById('previewTabs');

const PREVIEW_SCREENS = [
  { id: 'home', label: 'Home · TV' },
  { id: 'movies', label: 'Movies' },
  { id: 'series', label: 'Series' },
  { id: 'highlight', label: '★ Channel highlight' },
  { id: 'categories', label: 'Categories' },
  { id: 'guide', label: 'TV Guide' },
  { id: 'search', label: 'Search' },
  { id: 'movie-detail', label: 'Movie detail' },
  { id: 'series-detail', label: 'Series detail' },
  { id: 'settings', label: 'Settings' },
  { id: 'profiles', label: 'Profiles' },
  { id: 'picker', label: 'Profile picker' },
  { id: 'player', label: 'Player' },
  { id: 'welcome', label: 'Welcome' },
  { id: 'pin', label: 'PIN gate' },
];

const state = {
  screen: 'home',
  history: [],
  type: 'TV',
  hero: 0,
  profile: { name: 'Aarav', emoji: '🦊', color: '#5b7cfa' },
  favourites: new Set(['star-plus', 'colors-tv', 'star-sports-1']),
  watchlist: new Set(['kalki-2898-ad']),
  watched: new Set(['sacred-games:1:1', 'sacred-games:1:2', 'panchayat:1:1']),
  search: '',
  recents: ['star plus', 'cricket', 'panchayat'],
  settings: {
    trailers: true, afr: false, avsync: true, aspect: 'Fit',
    sourceType: 'M3U', m3u: 'https://iptv-org.github.io/iptv/countries/in.m3u',
    refresh: true, refreshHour: 3, kids: false, pin: '••••',
    audioLang: 'Hindi', subtitleLang: 'Off',
  },
  guideDay: 'Today',
  season: 1,
  player: { id: 'star-plus', playing: true, muted: true, panel: null, listOpen: false },
  category: null,
  detailMovie: 'jawan',
  detailSeries: 'the-family-man',
  highlightMode: 'original',
  highlightFocus: 'star-plus',
};

/* ----------------------------- Content data ----------------------------- */
const palettes = [
  ['#6e1633', '#1a0a12'], ['#143a5c', '#0a1018'], ['#4b2a0f', '#140d08'],
  ['#252c63', '#0c0e1c'], ['#0f4d47', '#081513'], ['#5b1d52', '#160a14'],
  ['#713522', '#170c08'], ['#1d3d24', '#08160d'], ['#3b2b62', '#100c1e'],
];
const art = (i) => `background:linear-gradient(135deg, ${palettes[i % palettes.length][0]}, ${palettes[i % palettes.length][1]} 70%)`;
const glyph = (name) => name.split(' ').filter(w => w.length > 2).slice(0, 2).map(w => w[0]).join('').toUpperCase();

const tv = [
  ['star-plus', 'Star Plus', 'Entertainment', 'Hindi', 'Yeh Rishta Kya Kehlata Hai', 'Anupamaa', 62, 'HD'],
  ['colors-tv', 'Colors TV', 'Entertainment', 'Hindi', 'Balika Vadhu 2', 'Big Boss Live', 35, 'HD'],
  ['sony-tv', 'Sony Entertainment Television', 'Entertainment', 'Hindi', 'Kaun Banega Crorepati', 'CID', 78, 'HD'],
  ['zee-tv', 'Zee TV', 'Entertainment', 'Hindi', 'Tujh Sang Preet', 'Sa Re Ga Ma Pa', 21, 'HD'],
  ['aaj-tak', 'Aaj Tak', 'News', 'Hindi', 'Top Stories', 'News Special', 45, 'HD'],
  ['india-today', 'India Today', 'News', 'Hindi', 'Prime Debate', 'World Update', 12, ''],
  ['ndtv-india', 'NDTV India', 'News', 'Hindi', 'Ground Report', 'Business Today', 81, ''],
  ['dd-national', 'DD National', 'Entertainment', 'Hindi', 'Ramayan', 'Mahabharat', 55, ''],
  ['star-sports-1', 'Star Sports 1', 'Sports', 'English', 'India vs Australia · 3rd ODI', 'Cricket Live', 40, 'HD'],
  ['star-sports-2', 'Star Sports 2', 'Sports', 'English', 'Football: ISL Highlights', 'Premier League', 67, 'HD'],
  ['sony-ten-1', 'Sony Ten 1', 'Sports', 'English', 'WWE Raw', 'UFC Fight Night', 30, 'HD'],
  ['star-gold', 'Star Gold', 'Movies', 'Hindi', 'Dangal', 'Bajrangi Bhaijaan', 72, 'HD'],
  ['zee-cinema', 'Zee Cinema', 'Movies', 'Hindi', 'Pathaan', 'Tiger 3', 18, 'HD'],
  ['star-vijay', 'Star Vijay', 'Regional', 'Tamil', 'Bigil', 'Vijay TV Show', 49, 'HD'],
  ['sun-tv', 'Sun TV', 'Regional', 'Tamil', 'Tamil Serial', 'Sun News', 26, 'HD'],
  ['asianet', 'Asianet', 'Regional', 'Malayalam', 'Malayalam Film', 'News Hour', 63, ''],
  ['zee-kannada', 'Zee Kannada', 'Regional', 'Kannada', 'Kannada Movie', 'Cook with Comali', 9, 'HD'],
  ['star-pravah', 'Star Pravah', 'Regional', 'Marathi', 'Marathi Serial', 'News', 38, ''],
  ['news18', 'News18 India', 'News', 'Hindi', 'Debate Tonight', 'Headlines', 90, 'HD'],
  ['pogo', 'Pogo', 'Kids', 'Hindi', 'Chhota Bheem', 'Mighty Raju', 15, ''],
  ['nick', 'Nickelodeon', 'Kids', 'English', 'SpongeBob', 'Motu Patlu', 52, 'HD'],
  ['discovery', 'Discovery India', 'Lifestyle', 'English', 'Wild India', 'Science Today', 73, 'HD'],
].map(([id, name, group, lang, now, next, progress, badge], i) => ({
  id, name, group, lang, now, next, progress, badge, type: 'TV', color: i,
}));

const movies = [
  ['jawan', 'Jawan', 'Action · Thriller', 2023, 'A high-octane action thriller where a vigilante takes on a corrupt system.', 169, 62, 8.1],
  ['pathaan', 'Pathaan', 'Action · Spy', 2023, 'A spy races against time to stop a deadly plot with global consequences.', 148, 0, 7.5],
  ['animal', 'Animal', 'Action · Drama', 2023, 'A son goes to extreme lengths to avenge his father.', 201, 34, 7.2],
  ['dangal', 'Dangal', 'Sports · Drama', 2016, 'A former wrestler trains his daughters to become world-class wrestlers.', 161, 0, 8.3],
  ['baahubali-2', 'Baahubali 2: The Conclusion', 'Epic · Action', 2017, 'The epic tale of revenge, duty and a kingdom torn apart.', 167, 0, 8.0],
  ['rrblock', 'RRR', 'Period · Action', 2022, 'Two revolutionary friends unite against an oppressive empire.', 187, 0, 7.9],
  ['kgf-2', 'KGF: Chapter 2', 'Action · Drama', 2022, 'Rocky raises the stakes in his quest for power in the gold fields.', 168, 0, 7.3],
  ['brahmastra', 'Brahmastra', 'Fantasy · Action', 2022, 'A young man discovers his destiny as part of an ancient astral weapon.', 167, 0, 6.8],
  ['kalki-2898-ad', 'Kalki 2898 AD', 'Sci-Fi · Epic', 2024, 'A mythic sci-fi odyssey set in a dystopian future.', 181, 0, 7.6],
  ['laapataa-ladies', 'Laapataa Ladies', 'Comedy · Drama', 2024, 'Two brides get swapped on a train and find their own paths.', 122, 0, 7.4],
  ['vikram-vedha', 'Vikram Vedha', 'Crime · Thriller', 2022, 'A cop and a gangster play a dangerous game of cat and mouse.', 154, 0, 7.4],
  ['sultan', 'Sultan', 'Sports · Drama', 2016, 'A wrestler returns to the arena to reclaim his life.', 130, 0, 6.9],
  ['mission-majnu', 'Mission Majnu', 'Spy · Thriller', 2023, 'An undercover agent operates deep inside enemy territory.', 125, 0, 6.4],
  ['ponniyin-selvan', 'Ponniyin Selvan', 'Epic · Drama', 2022, 'A sweeping historical saga of the Chola dynasty.', 169, 0, 7.0],
  ['beast', 'Beast', 'Action · Comedy', 2022, 'A hostage situation turns into a wild ride in a shopping mall.', 144, 0, 6.4],
  ['bajrangi-bhaijaan', 'Bajrangi Bhaijaan', 'Drama · Comedy', 2015, 'A man risks everything to reunite a lost girl with her family.', 155, 0, 8.0],
  ['2-point-0', '2.0', 'Sci-Fi · Action', 2018, 'A scientist faces a powerful force that threatens humanity.', 147, 0, 6.6],
  ['darbar', 'Darbar', 'Action · Thriller', 2020, 'A fearless cop takes on a dangerous criminal network.', 152, 0, 6.2],
].map(([id, name, genre, year, desc, mins, progress, rating], i) => ({
  id, name, genre, year, desc, mins, progress, rating, type: 'MOVIE', color: i + 2,
}));

const series = [
  ['the-family-man', 'The Family Man', 'Action · Thriller', 2019, 'A middle-aged spy balances family life with high-stakes missions.', 3, 8.7],
  ['mirzapur', 'Mirzapur', 'Crime · Thriller', 2018, 'Power, greed and violence rule a small town in Uttar Pradesh.', 3, 8.4],
  ['panchayat', 'Panchayat', 'Comedy · Drama', 2020, 'An engineer posted to a remote village finds his own place in it.', 4, 8.8],
  ['sacred-games', 'Sacred Games', 'Crime · Thriller', 2018, 'A police officer investigates a gangster with a dangerous secret.', 2, 8.4],
  ['paatal-lok', 'Paatal Lok', 'Crime · Drama', 2020, 'A skeptical cop investigates a high-profile case gone wrong.', 2, 8.1],
  ['scam-1992', 'Scam 1992', 'Drama · Biopic', 2020, 'The rise and fall of a stock market broker who changed the game.', 1, 9.3],
  ['delhi-crime', 'Delhi Crime', 'Crime · Drama', 2019, 'A police team races to solve a shocking case in the capital.', 2, 8.2],
  ['farzi', 'Farzi', 'Crime · Comedy', 2023, 'An artist with a gift for forgery gets pulled into a web of crime.', 2, 7.9],
  ['kota-factory', 'Kota Factory', 'Comedy · Drama', 2019, 'The ups and downs of students chasing a dream in Kota.', 3, 8.6],
  ['made-in-heaven', 'Made in Heaven', 'Drama · Romance', 2019, 'Two wedding planners navigate love, secrets and high expectations.', 2, 7.9],
  ['special-ops', 'Special Ops', 'Action · Thriller', 2020, 'A raw agent hunts a mastermind behind a string of attacks.', 1, 8.0],
  ['gullak', 'Gullak', 'Comedy · Family', 2019, 'The everyday joys and squabbles of a middle-class family.', 4, 9.0],
].map(([id, name, genre, year, desc, seasons, rating], i) => ({
  id, name, genre, year, desc, seasons, rating, type: 'SERIES', color: i + 4,
}));

const allItems = [...tv, ...movies, ...series];
const byId = (id) => allItems.find(x => x.id === id) || tv[0];
const episodes = (seriesId, season) => {
  const titles = {
    1: ['The Call', 'A New Lead', 'Under Surveillance', 'Crossfire', 'The Trade', 'Silence', 'The Trap', 'Endgame'],
    2: ['Homecoming', 'The Promise', 'Shadows', 'The Deal', 'Reckoning', 'Fallen', 'Rebirth', 'The Last Stand'],
    3: ['A Fresh Start', 'Fault Lines', 'The Informant', 'Double Cross', 'Blackout', 'The Verdict', 'Aftermath', 'New Beginnings'],
    4: ['Village Life', 'The Inspection', 'Monsoon', 'A Small Victory', 'Visitors', 'The Meeting', 'Election Day', 'A New Chapter'],
  };
  return (titles[season] || titles[1]).map((title, i) => ({
    n: i + 1, title,
    desc: `${byId(seriesId).name} — an important turn in the story as new secrets come to light.`,
    watched: state.watched.has(`${seriesId}:${season}:${i + 1}`),
    progress: i === 1 && season === 1 ? 42 : 0,
  }));
};

/* ------------------------------ Utilities ------------------------------ */
function toast(message) {
  $toast.textContent = message;
  $toast.classList.add('is-show');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => $toast.classList.remove('is-show'), 2100);
}
function navigate(screen, opts = {}) {
  if (state.screen !== screen) state.history.push(state.screen);
  state.screen = screen;
  if (opts.type) state.type = opts.type;
  if (opts.id && screen === 'movie-detail') state.detailMovie = opts.id;
  if (opts.id && screen === 'series-detail') state.detailSeries = opts.id;
  if (opts.id && screen === 'player') state.player.id = opts.id;
  if (screen !== 'search' && !opts.keepSearch) state.search = '';
  render();
  window.scrollTo({ top: 0, behavior: 'instant' });
}
function back() {
  const prev = state.history.pop() || 'home';
  state.screen = prev === 'movies' || prev === 'series' ? 'home' : prev;
  render();
}
function openItem(id) {
  const item = byId(id);
  if (!item) return;
  if (item.type === 'TV') navigate('player', { id });
  else if (item.type === 'MOVIE') navigate('movie-detail', { id });
  else navigate('series-detail', { id });
}
function toggleSet(setName, id, label) {
  const set = state[setName];
  const has = set.has(id);
  has ? set.delete(id) : set.add(id);
  toast(`${has ? 'Removed from' : 'Added to'} ${label}`);
  render();
}

/* ------------------------------ Components ------------------------------ */
function topbar() {
  const p = state.profile;
  return `
    <header class="topbar">
      <div class="topbar__logo">Indian IPTV<small>EXPERIMENT BUILD</small></div>
      <button class="pill pill--icon" data-action="search" title="Search">⌕</button>
      <button class="pill" data-action="categories">Categories</button>
      <button class="pill" data-action="guide">Guide</button>
      <button class="pill pill--profile" data-action="profiles">
        <span class="avatar" style="background:${p.color}">${p.emoji}</span>${p.name}
      </button>
      <button class="pill pill--icon" data-action="settings" title="Settings">⋮</button>
      <button class="pill pill--status" data-action="settings"><span class="status-dot"></span>Updated just now</button>
    </header>`;
}
function typetabs() {
  return `<div class="typetabs">
    ${[['TV', 'TV'], ['MOVIES', 'Movies'], ['SERIES', 'Series']].map(([key, label]) =>
      `<button class="typetab ${state.type === key ? 'is-active' : ''}" data-action="type" data-type="${key}">${label}</button>`).join('')}
  </div>`;
}
function card(item, opts = {}) {
  const fav = state.favourites.has(item.id);
  const watch = state.watchlist.has(item.id);
  const badges = [
    item.badge === 'HD' ? '<span class="badge badge--hd">HD</span>' : '',
    opts.isNew ? '<span class="badge badge--new">NEW</span>' : '',
    fav ? '<span class="badge badge--fav">★</span>' : '',
  ].join('');
  const meta = item.type === 'TV'
    ? `${item.group} · ${item.lang}`
    : item.type === 'MOVIE'
      ? `${item.genre} · ${item.year}`
      : `${item.genre} · ${item.seasons} season${item.seasons > 1 ? 's' : ''}`;
  const progress = item.type === 'TV' ? item.progress : item.progress;
  return `<button class="card ${opts.wide ? 'card--wide' : ''} ${opts.cls || ''}" data-action="${opts.action || 'open'}" data-id="${item.id}" aria-label="${item.name}">
    <div class="card__art" style="${art(item.color)}">
      <span class="card__glyph">${glyph(item.name)}</span>
      <div class="card__badges">${badges}</div>
      ${item.type === 'TV' ? `<span class="card__logo">${glyph(item.name)}</span>` : ''}
      <div class="card__hover">
        <div class="mini-actions">
          <button data-action="open" data-id="${item.id}">▶ ${item.type === 'TV' ? 'Watch' : 'More info'}</button>
          <button data-action="fav" data-id="${item.id}">${fav ? '★ Remove' : '☆ Favourite'}</button>
          <button data-action="watchlist" data-id="${item.id}">${watch ? '✓ Saved' : '+ Later'}</button>
        </div>
        <p>${item.type === 'TV' ? 'Now: ' + item.now : item.desc}</p>
      </div>
    </div>
    <div class="card__body">
      <div class="card__name">${item.name}</div>
      <div class="card__meta">${meta}</div>
      ${progress ? `<div class="card__bar"><i style="width:${progress}%"></i></div>` : ''}
    </div>
  </button>`;
}
function rail(title, items, opts = {}) {
  if (!items.length) return '';
  return `<section class="rail">
    <div class="rail__head">
      <h2 class="rail__title"><i>▌</i>${title}</h2>
      ${opts.seeAll ? `<button class="rail__seeall" data-action="categories">See All ›</button>` : ''}
      <span class="rail__sub">${items.length} titles ${opts.hint ? '· ' + opts.hint : ''}</span>
    </div>
    <div class="rail__scroll">${items.map(i => card(i, opts)).join('')}</div>
  </section>`;
}

/* -------------------------------- Screens -------------------------------- */
function heroItems() {
  if (state.type === 'TV') return [tv[8], tv[0], tv[4]];
  if (state.type === 'MOVIES') return [movies[0], movies[8], movies[1]];
  return [series[0], series[1], series[2]];
}
function homeScreen() {
  const heroes = heroItems();
  const h = heroes[state.hero % heroes.length];
  const isLive = h.type === 'TV';
  const desc = isLive ? `Now on ${h.name}: ${h.now}. Up next: ${h.next}.` : h.desc;
  const meta = isLive ? `${h.group} · ${h.lang} · HD` : `${h.genre} · ${h.year} · ★ ${h.rating}`;
  let rails;
  if (state.type === 'TV') {
    rails = [
      rail('Continue Watching', movies.slice(0, 4).concat(series.slice(0, 2)), { wide: true, hint: 'auto-resume' }),
      rail('Popular Now', tv.slice(0, 8), { hint: 'via TMDB' }),
      rail('My List', [...movies, ...series].filter(i => state.watchlist.has(i.id)), { hint: 'save for later' }),
      rail('Favorites', tv.filter(i => state.favourites.has(i.id)), { hint: 'OK toggles' }),
      rail('All Channels', tv.slice(0, 14), { seeAll: true }),
      rail('News', tv.filter(i => i.group === 'News')),
      rail('Sports', tv.filter(i => i.group === 'Sports')),
      rail('Entertainment', tv.filter(i => i.group === 'Entertainment')),
      rail('Regional', tv.filter(i => i.group === 'Regional')),
      rail('Kids', tv.filter(i => i.group === 'Kids')),
      rail('New in Catalogue', tv.slice(-6), { hint: 'NEW badge' }),
    ].join('');
  } else if (state.type === 'MOVIES') {
    rails = [
      rail('Continue Watching', movies.filter(i => i.progress > 0), { wide: true, hint: 'auto-resume' }),
      rail('Popular Now', movies.slice(0, 8), { hint: 'via TMDB' }),
      rail('My List', movies.filter(i => state.watchlist.has(i.id))),
      rail('Save for Later', movies.slice(8, 13)),
      rail('New in Catalogue', movies.slice(8, 14), { hint: 'NEW badge' }),
      rail('Top 10 This Week', movies.slice(0, 10)),
      rail('Action Thrillers', movies.filter(i => i.genre.includes('Action'))),
      rail('Drama', movies.filter(i => i.genre.includes('Drama'))),
      rail('More Like This', movies.slice(4, 11)),
    ].join('');
  } else {
    rails = [
      rail('Continue Watching', series.slice(0, 3), { wide: true, hint: 'S1:E2 · 42% watched' }),
      rail('Popular Now', series.slice(0, 8), { hint: 'via TMDB' }),
      rail('My List', series.filter(i => state.watchlist.has(i.id))),
      rail('Save for Later', series.slice(6, 11)),
      rail('New in Catalogue', series.slice(7)),
      rail('Top 10 This Week', series.slice(0, 10)),
      rail('Crime & Thriller', series.filter(i => i.genre.includes('Crime') || i.genre.includes('Thriller'))),
      rail('Comedy', series.filter(i => i.genre.includes('Comedy'))),
      rail('More Like This', series.slice(3, 10)),
    ].join('');
  }
  return `<div class="screen">
    ${topbar()}
    ${typetabs()}
    <section class="hero" style="background:${art(h.color)}">
      <div class="hero__art">${glyph(h.name)}</div>
      <button class="hero__sound" data-action="sound">${state.settings.trailers ? '🔊 Sound On' : '🔇 Sound Off'}</button>
      <div class="hero__content">
        <div class="hero__eyebrow"><span class="live-dot"></span>${isLive ? 'NOW ON · ' + h.name : 'FEATURED · ' + h.genre}</div>
        <h1 class="hero__title">${h.name}</h1>
        <div class="hero__meta"><span>★ ${h.rating || 'HD'}</span><span>${meta}</span><span>${h.mins ? Math.floor(h.mins / 60) + 'h ' + (h.mins % 60) + 'm' : 'Live'}</span></div>
        ${!isLive && h.progress ? `<div class="hero__progress"><i style="width:${h.progress}%"></i></div>` : ''}
        <p class="hero__desc">${desc}</p>
        <div class="hero__actions">
          <button class="btn btn--primary" data-action="open" data-id="${h.id}">▶ ${isLive ? 'Watch Live' : h.progress ? 'Resume' : 'Play'}</button>
          <button class="btn btn--ghost" data-action="open" data-id="${h.id}">ⓘ More Info</button>
          <button class="btn btn--ghost" data-action="watchlist" data-id="${h.id}">${state.watchlist.has(h.id) ? '✓ In My List' : '+ My List'}</button>
        </div>
      </div>
      <div class="hero__nav">${heroes.map((_, i) => `<button class="hero__dot ${i === state.hero % heroes.length ? 'is-active' : ''}" data-action="hero" data-index="${i}" aria-label="Hero ${i + 1}"></button>`).join('')}</div>
    </section>
    ${rails}
  </div>`;
}
function highlightCard(item, mode, focused, clickId) {
  const cls = focused ? (mode === 'original' ? 'hl-accent' : 'hl-white') : '';
  return `<button class="card card--wide ${cls}" data-action="hl-focus" data-id="${clickId}" aria-label="${item.name}">
    <div class="card__art" style="${art(item.color)}">
      <span class="card__glyph">${glyph(item.name)}</span>
      <div class="card__badges"><span class="badge badge--hd">HD</span>${state.favourites.has(item.id) ? '<span class="badge badge--fav">★</span>' : ''}</div>
      <span class="card__logo">${glyph(item.name)}</span>
    </div>
    <div class="card__body"><div class="card__name">${item.name}</div><div class="card__meta">${item.group} · ${item.lang} · ${item.now}</div></div>
  </button>`;
}
function highlightScreen() {
  const demo = tv.slice(0, 6);
  const original = state.highlightMode === 'original';
  return `<div class="screen">
    ${topbar()}
    <div class="screen__inner">
      <button class="back-link" data-action="home">‹ Back to home</button>
      <h1 class="section-title">Channel highlighting · original Android TV</h1>
      <p class="section-sub">The focus treatment from before the TV UI edits — restored from the pre-experiment code in git.</p>

      <section class="panel">
        <div class="panel__head"><h2>LIVE DEMO</h2><span class="tag">tap a channel to move focus</span></div>
        <div class="segmented">
          <button class="${original ? 'is-active' : ''}" data-action="hl-mode" data-mode="original">Original (unedited)</button>
          <button class="${!original ? 'is-active' : ''}" data-action="hl-mode" data-mode="experiment">Experiment (current)</button>
        </div>
        <div class="hl-note ${original ? 'hl-note--red' : ''}">
          ${original
            ? '● 2 dp gradient border (Accent → AccentSoft) with a 12 dp red glow. Softer 1.08× spring (StiffnessMediumLow).'
            : '○ 3 dp solid white ring with a 16 dp shadow. This is what commit 9d2defe changed it to.'}
        </div>
        <div class="rail__scroll hl-demo">${demo.map(c => highlightCard(c, state.highlightMode, state.highlightFocus === c.id, c.id)).join('')}</div>
      </section>

      <section class="panel">
        <div class="panel__head"><h2>BEFORE / AFTER</h2><span class="tag">same card, same artwork</span></div>
        <div class="hl-compare">
          <div class="hl-compare__col">
            <h3>Original · unedited TV app</h3>
            <div class="hl-demo">${highlightCard(tv[0], 'original', true, 'compare-red')}</div>
            <ul><li>2 dp border, linear gradient Accent → AccentSoft</li><li>12 dp shadow · ambient 0.30 · spot 0.85</li><li>Focus scale spring: StiffnessMediumLow</li></ul>
          </div>
          <div class="hl-compare__col">
            <h3>Experiment build · current</h3>
            <div class="hl-demo">${highlightCard(tv[0], 'experiment', true, 'compare-white')}</div>
            <ul><li>3 dp solid white border</li><li>16 dp shadow · ambient 0.35 · spot 0.95</li><li>Focus scale spring: StiffnessMedium</li></ul>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel__head"><h2>HOW IT IS IMPLEMENTED</h2><span class="tag">FocusableCard in ChannelsScreen.kt</span></div>
        <pre class="hl-code">Modifier.border(
    width = 2.dp,
    brush = Brush.linearGradient(
        listOf(IptvPalette.Accent, IptvPalette.AccentSoft),
    ),
    shape = shape,
)
Modifier.shadow(
    elevation = 12.dp,
    shape = shape,
    ambientColor = IptvPalette.AccentSoft.copy(alpha = 0.30f),
    spotColor = IptvPalette.AccentSoft.copy(alpha = 0.85f),
)</pre>
      </section>
    </div>
  </div>`;
}
function categoriesScreen() {
  const cats = [...new Set(allItems.map(i => i.group))];
  const focus = state.category;
  const items = focus ? allItems.filter(i => i.group === focus) : [];
  return `<div class="screen">
    ${topbar()}
    <div class="screen__inner">
      ${focus ? `<button class="back-link" data-action="cat-back">‹ All categories</button>` : ''}
      <h1 class="section-title">${focus ? focus : 'All Categories'}${focus ? ` · ${items.length}` : ''}</h1>
      <p class="section-sub">${focus ? 'Browse everything in this category' : 'All categorieën · every channel, movie and series'}</p>
      ${focus ? `<div class="grid grid--cats">${items.map(i => card(i, { wide: false })).join('')}</div>` :
        `<div class="grid grid--cats">${cats.map((c, i) => `<button class="cat-card" data-action="category" data-name="${c}">
          <span class="cat-card__emoji">${['📺','🎬','📰','🏏','🎭','🧒','🌍','🎵','🗞️'][i % 9]}</span>
          <strong>${c}</strong><small>${allItems.filter(x => x.group === c).length} titles</small>
        </button>`).join('')}</div>`}
    </div>
  </div>`;
}
function guideScreen() {
  const times = ['18:00', '18:30', '19:00', '19:30', '20:00', '20:30', '21:00'];
  const rows = tv.slice(0, 10).map((ch, i) => {
    const cells = times.map((t, j) => {
      const title = j === 2 ? ch.now : (j === 3 ? ch.next : ['Local News', 'Special Report', 'Movie', 'Live Show', 'Highlights', 'Analysis', 'Tonight'][(i + j) % 7]);
      return `<button class="guide-cell ${j === 2 ? 'guide-cell--now' : j === 3 ? 'guide-cell--soon' : ''}" data-action="reminder" data-name="${title}" data-channel="${ch.name}">
        <strong>${title}</strong>${t} · 30 min${j === 2 ? ' · LIVE' : ''}</button>`;
    }).join('');
    return `<div class="guide-row">
      <div class="guide-row__channel"><span class="avatar" style="background:${art(ch.color).match(/#\w{6}/)[0]}">${glyph(ch.name)}</span><span>${ch.name}</span></div>
      <div class="guide-row__programmes">${cells}</div>
    </div>`;
  }).join('');
  return `<div class="screen">
    ${topbar()}
    <div class="screen__inner">
      <h1 class="section-title">TV Guide</h1>
      <p class="section-sub">Press OK on a programme to set a reminder · ${state.guideDay}</p>
      <div class="segmented" style="display:inline-flex;margin-bottom:14px">
        ${['Yesterday', 'Today', 'Tomorrow'].map(d => `<button data-action="guide-day" data-day="${d}" class="${state.guideDay === d ? 'is-active' : ''}">${d}</button>`).join('')}
      </div>
    </div>
    <div class="guide-timebar">
      <div class="guide-timebar__channel">CHANNEL</div>
      <div class="guide-timebar__times">${times.map((t, i) => `<div class="guide-time ${i === 2 ? 'guide-time--now' : ''}">${t}</div>`).join('')}</div>
    </div>
    <div class="guide-scroll">${rows}</div>
    <div class="screen__inner" style="margin-top:16px;display:flex;gap:8px">
      <button class="pill" data-action="toast" data-message="Showing earlier programmes">◀ Earlier</button>
      <button class="pill" data-action="toast" data-message="Jumped to now">Now</button>
      <button class="pill" data-action="toast" data-message="Showing later programmes">Later ▶</button>
    </div>
  </div>`;
}
function searchScreen() {
  const q = state.search.trim().toLowerCase();
  const results = q ? allItems.filter(i => (i.name + ' ' + i.group + ' ' + (i.genre || '') + ' ' + i.lang).toLowerCase().includes(q)) : [];
  const groups = [
    ['Channels (' + results.filter(i => i.type === 'TV').length + ')', results.filter(i => i.type === 'TV')],
    ['Movies (' + results.filter(i => i.type === 'MOVIE').length + ')', results.filter(i => i.type === 'MOVIE')],
    ['Series (' + results.filter(i => i.type === 'SERIES').length + ')', results.filter(i => i.type === 'SERIES')],
  ];
  return `<div class="screen">
    ${topbar()}
    <div class="searchbar"><span>⌕</span><input id="searchInput" value="${state.search.replace(/"/g, '&quot;')}" placeholder="Search channels, movies and series..." autocomplete="off" /><button class="pill" data-action="clear-search">Clear</button></div>
    <div class="chip-row"><span style="font-size:11px;color:var(--text-3);align-self:center;margin-right:4px">Recent</span>
      ${state.recents.map(r => `<button class="chip" data-action="recent" data-value="${r}">${r}</button>`).join('')}
      <button class="chip" data-action="clear-recents">Clear recent</button>
    </div>
    ${!q ? `<div class="empty"><strong>Search everything</strong>Try “star plus”, “cricket”, “action” or “panchayat”.</div>` :
      results.length ? groups.map(([label, items]) => items.length ? `<section class="result-group"><h3>${label}</h3><div class="rail__scroll">${items.map(i => card(i)).join('')}</div></section>` : '').join('') :
      `<div class="empty"><strong>No results for “${state.search}”.</strong>Try a different spelling or a genre.</div>`}
  </div>`;
}
function movieDetailScreen() {
  const m = byId(state.detailMovie);
  const related = movies.filter(x => x.id !== m.id).slice(0, 10);
  const inList = state.watchlist.has(m.id);
  return `<div class="screen">
    <div class="detail-hero" style="background:${art(m.color)}">
      <div class="detail-hero__art">${glyph(m.name)}</div>
      <div class="detail-hero__content">
        <button class="back-link" data-action="back">‹ Back</button>
        <h1>${m.name}</h1>
        <div class="hero__meta"><span>★ ${m.rating}</span><span>${m.year}</span><span>${m.genre}</span><span>${Math.floor(m.mins / 60)}h ${m.mins % 60}m</span><span>HD</span></div>
        <div class="hero__actions">
          <button class="btn btn--primary" data-action="open" data-id="${m.id}">▶ ${m.progress ? 'Resume · ' + m.progress + '%' : 'Play'}</button>
          <button class="btn btn--ghost" data-action="watchlist" data-id="${m.id}">${inList ? '✓ In My List' : '+ Add to My List'}</button>
          <button class="btn btn--ghost" data-action="watchlist" data-id="${m.id}">${inList ? '✓ Watch Later' : '🕒 Watch Later'}</button>
        </div>
      </div>
    </div>
    <div class="detail-body">
      <div class="detail-cols">
        <div>
          <h3>Story</h3><p>${m.desc}</p>
          ${rail('More Like This', related)}
        </div>
        <div>
          <h3>Details</h3>
          <div class="fact"><b>Genre:</b> ${m.genre}</div>
          <div class="fact"><b>Year:</b> ${m.year}</div>
          <div class="fact"><b>Runtime:</b> ${Math.floor(m.mins / 60)}h ${m.mins % 60}m</div>
          <div class="fact"><b>Rating:</b> ★ ${m.rating} / 10</div>
          <div class="fact"><b>Audio:</b> Hindi, English</div>
          <div class="fact"><b>Subtitles:</b> English</div>
          <div class="fact"><b>Availability:</b> Included with your source</div>
          <p class="section-sub" style="margin-top:16px">This product uses the TMDB API but is not endorsed or certified by TMDB.</p>
        </div>
      </div>
    </div>
  </div>`;
}
function seriesDetailScreen() {
  const s = byId(state.detailSeries);
  const eps = episodes(s.id, state.season);
  const related = series.filter(x => x.id !== s.id).slice(0, 8);
  const inList = state.watchlist.has(s.id);
  return `<div class="screen">
    <div class="detail-hero" style="background:${art(s.color)}">
      <div class="detail-hero__art">${glyph(s.name)}</div>
      <div class="detail-hero__content">
        <button class="back-link" data-action="back">‹ Back</button>
        <h1>${s.name}</h1>
        <div class="hero__meta"><span>★ ${s.rating}</span><span>${s.year}</span><span>${s.genre}</span><span>${s.seasons} seasons</span><span>HD</span></div>
        <div class="hero__actions">
          <button class="btn btn--primary" data-action="play-episode" data-id="${s.id}">▶ Continue S1:E2</button>
          <button class="btn btn--ghost" data-action="watchlist" data-id="${s.id}">${inList ? '✓ In My List' : '+ My List'}</button>
          <button class="btn btn--ghost" data-action="toast" data-message="Trailer playing (muted)">▶ Trailer</button>
        </div>
      </div>
    </div>
    <div class="detail-body">
      <h3 style="font-size:13px;letter-spacing:1.5px;color:var(--text-3)">STORY</h3>
      <p style="font-size:14px;line-height:1.7;color:var(--text-2);max-width:760px">${s.desc}</p>
      <div class="season-tabs">${Array.from({ length: s.seasons }, (_, i) => `<button class="chip ${state.season === i + 1 ? 'is-active' : ''}" data-action="season" data-n="${i + 1}" style="${state.season === i + 1 ? 'background:var(--accent);border-color:var(--accent);color:#fff' : ''}">Season ${i + 1}</button>`).join('')}
        <span class="rail__sub">${eps.filter(e => e.watched).length} of ${eps.length} watched</span>
      </div>
      <div style="margin-bottom:26px">${eps.map(e => `<button class="episode" data-action="play-episode" data-id="${s.id}">
        <span class="episode__thumb" style="${art(s.color + e.n)}">S${state.season}:E${e.n}</span>
        <span class="episode__info"><strong>${e.n}. ${e.title}</strong><p>${e.desc}</p>${e.watched ? '<span class="episode__mark">✓ Watched</span>' : ''}${e.progress ? `<div class="card__bar" style="max-width:180px"><i style="width:${e.progress}%"></i></div>` : ''}</span>
        <span style="align-self:center;font-size:20px">▶</span>
      </button>`).join('')}</div>
      ${rail('More Like This', related)}
    </div>
  </div>`;
}
function settingsScreen() {
  const s = state.settings;
  const toggle = (key, label, body) => `<div class="setting-row"><div class="setting-row__text"><strong>${label}</strong><small>${body}</small></div>
    <button class="toggle ${s[key] ? 'is-on' : ''}" data-action="toggle" data-key="${key}" aria-label="${label}"><i></i></button></div>`;
  return `<div class="screen">
    ${topbar()}
    <div class="screen__inner">
      <h1 class="section-title">Settings</h1>
      <p class="section-sub">Configure the source, playback and profiles</p>
      <section class="panel">
        <div class="panel__head"><h2>SOURCE</h2><span class="tag">saved locally</span></div>
        <div class="segmented">${['M3U', 'Xtream'].map(t => `<button class="${s.sourceType === t ? 'is-active' : ''}" data-action="source" data-value="${t}">${t}</button>`).join('')}</div>
        <label class="field-label">${s.sourceType === 'M3U' ? 'M3U URL' : 'Host'}</label>
        <input class="field" value="${s.sourceType === 'M3U' ? s.m3u : 'http://provider.tv:8080'}" />
        ${s.sourceType === 'Xtream' ? `<label class="field-label">Username / Password</label><input class="field" value="demo_user" /><input class="field" type="password" value="••••••••" style="margin-top:8px" />` : ''}
        <button class="pill" data-action="add-playlist" style="margin-top:10px">+ Add playlist</button>
        <div class="btn-row"><button class="btn btn--accent" data-action="toast" data-message="Connection works: 122 channels, 18 movies, 12 series found">Test Connection</button><button class="btn btn--ghost" data-action="toast" data-message="Saved">Save</button></div>
      </section>
      <section class="panel">
        <div class="panel__head"><h2>ENTER WITH YOUR PHONE</h2></div>
        <div style="display:flex;gap:16px;align-items:center"><div class="qr-mock"></div><div><strong style="font-size:13px">Scan to enter source details</strong><p class="section-sub" style="margin:4px 0">Same Wi-Fi · token-protected LAN setup form</p></div></div>
      </section>
      <section class="panel">
        <div class="panel__head"><h2>DISPLAY & PLAYBACK</h2></div>
        ${toggle('trailers', 'Auto-play Trailers', 'Start a muted trailer on the home screen after a few seconds.')}
        ${toggle('afr', 'Adjust Frame Rate', 'Match the display refresh rate to the content (24/25/30/50/60 fps).')}
        ${toggle('avsync', 'Sync Video & Audio via Hardware', 'Tunneled playback keeps A/V in sync. Turn off if a channel stutters.')}
        <div class="setting-row"><div class="setting-row__text"><strong>Default Aspect Ratio</strong><small>Fit, Fill or Zoom for all channels</small></div>
          <div class="segmented">${['Fit', 'Fill', 'Zoom'].map(a => `<button class="${s.aspect === a ? 'is-active' : ''}" data-action="aspect" data-value="${a}">${a}</button>`).join('')}</div></div>
        <div class="setting-row"><div class="setting-row__text"><strong>Preferred Audio Language</strong><small>Used when a stream offers multiple tracks</small></div>
          <div class="segmented">${['Hindi', 'English', 'Any'].map(a => `<button class="${s.audioLang === a ? 'is-active' : ''}" data-action="audio-lang" data-value="${a}">${a}</button>`).join('')}</div></div>
      </section>
      <section class="panel">
        <div class="panel__head"><h2>REFRESH</h2></div>
        ${toggle('refresh', 'Auto Refresh', 'Update the catalogue automatically every night while you sleep.')}
        <div class="setting-row"><div class="setting-row__text"><strong>Refresh time</strong><small>Nightly catalogue check</small></div>
          <div class="segmented"><button data-action="hour" data-d="-1">− 1h</button><button class="is-active">${String(s.refreshHour).padStart(2, '0')}:00</button><button data-action="hour" data-d="1">+ 1h</button></div></div>
        <button class="btn btn--ghost" data-action="toast" data-message="Refreshing catalogue... 122 channels loaded">Refresh Now</button>
      </section>
      <section class="panel">
        <div class="panel__head"><h2>PARENTAL CONTROL & PROFILES</h2></div>
        ${toggle('kids', 'Kids Profile', 'Hides adult categories. Switching profiles requires the PIN.')}
        <div class="setting-row"><div class="setting-row__text"><strong>PIN</strong><small>Required to open Settings from a kids profile</small></div><button class="pill" data-action="toast" data-message="PIN pad opened">Set</button></div>
        <button class="pill" data-action="profiles">Manage profiles ›</button>
      </section>
      <section class="panel">
        <div class="panel__head"><h2>ABOUT</h2></div>
        <div class="fact">Open-source under the Apache License 2.0.</div>
        <div class="fact">Movie and series data from The Movie Database (TMDB).</div>
        <div class="fact">This product uses the TMDB API but is not endorsed or certified by TMDB.</div>
        <div class="fact">Not affiliated with Formuler. “Formuler” and “Z10 Pro Max” are trademarks of their respective owners.</div>
      </section>
    </div>
  </div>`;
}
function profilesScreen() {
  const people = [
    { name: 'Aarav', emoji: '🦊', color: '#5b7cfa', sub: 'Active profile' },
    { name: 'Priya', emoji: '🌸', color: '#e05a8a', sub: '12 favourites' },
    { name: 'Rohan', emoji: '🦁', color: '#e0a13a', sub: '8 hours watched' },
    { name: 'Kids', emoji: '🧒', color: '#4ecb8a', sub: state.settings.kids ? 'Kids profile · PIN required' : 'Kids profile' },
  ];
  return `<div class="screen">
    ${topbar()}
    <div class="screen__inner">
      <button class="back-link" data-action="back">‹ Back</button>
      <h1 class="section-title">Profiles</h1>
      <p class="section-sub">Each user gets their own list, favorites and progress</p>
      <div class="grid grid--profiles">${people.map(p => `<button class="profile-card ${state.profile.name === p.name ? 'is-active' : ''}" data-action="pick-profile" data-name="${p.name}">
        <span class="profile-card__avatar" style="background:${p.color}">${p.emoji}</span>
        <strong>${p.name}</strong><small>${p.name === state.profile.name ? 'Active profile' : p.sub}</small>
      </button>`).join('')}
      <button class="profile-card" data-action="toast" data-message="New profile created"><span class="profile-card__avatar" style="background:var(--surface-elevated)">＋</span><strong>Add Profile</strong><small>Create a new viewer</small></button>
      </div>
      <div class="panel" style="margin-top:20px;max-width:520px">
        <div class="panel__head"><h2>EDIT PROFILE</h2></div>
        <label class="field-label">Name</label><input class="field" value="${state.profile.name}" />
        <label class="field-label">Avatar</label><div class="chip-row" style="padding:0">${['🦊','🌸','🦁','🧒','🐯','🐼','🚀','🎬'].map(e => `<button class="chip" data-action="toast" data-message="Avatar changed to ${e}">${e}</button>`).join('')}</div>
        <div class="btn-row"><button class="btn btn--ghost" data-action="toast" data-message="Profile saved">Save changes</button><button class="btn btn--ghost" data-action="toast" data-message="Delete needs confirmation">Delete profile</button></div>
      </div>
    </div>
  </div>`;
}
function pickerScreen() {
  const people = [
    { name: 'Aarav', emoji: '🦊', color: '#5b7cfa' }, { name: 'Priya', emoji: '🌸', color: '#e05a8a' },
    { name: 'Rohan', emoji: '🦁', color: '#e0a13a' }, { name: 'Kids', emoji: '🧒', color: '#4ecb8a' },
  ];
  return `<div class="picker">
    <h1>Who is Watching?</h1><p>Cold-start profile picker · pick a profile to continue</p>
    <div class="grid grid--profiles">${people.map(p => `<button class="profile-card" data-action="pick-profile" data-name="${p.name}">
      <span class="profile-card__avatar" style="background:${p.color}">${p.emoji}</span><strong>${p.name}</strong>
    </button>`).join('')}</div>
    <button class="pill" style="margin-top:22px" data-action="profiles">Manage profiles</button>
  </div>`;
}
function playerScreen() {
  const item = byId(state.player.id);
  const p = state.player;
  const isLive = item.type === 'TV';
  const panel = p.panel;
  let overlay = '';
  if (panel === 'tracks') overlay = `<div class="panel-overlay"><button class="close-x" data-action="close-panel">×</button><h3>Audio / Subtitles</h3><ul>
    <li class="is-active">Audio: Hindi (AC3)</li><li>Audio: English (AAC)</li><li>Subtitles: Off</li><li>Subtitles: English</li><li>Subtitle delay: +0.0 s ◀ ▶</li></ul></div>`;
  if (panel === 'stats') overlay = `<div class="panel-overlay"><button class="close-x" data-action="close-panel">×</button><h3>Stream Info</h3>
    <div class="stat-line"><span>Resolution</span><b>1920 × 1080</b></div><div class="stat-line"><span>Bitrate</span><b>4.8 Mbps</b></div>
    <div class="stat-line"><span>Codec</span><b>H264 / AAC</b></div><div class="stat-line"><span>Buffer</span><b>12.4 s</b></div>
    <div class="stat-line"><span>Dropped frames</span><b>0</b></div></div>`;
  if (p.listOpen) overlay = `<div class="panel-overlay"><button class="close-x" data-action="close-panel">×</button><h3>Channel List · Favorites</h3><ul>
    ${tv.slice(0, 8).map(c => `<li class="${c.id === item.id ? 'is-active' : ''}" data-action="play-channel" data-id="${c.id}">${c.name} <small style="opacity:.6">${c.now}</small></li>`).join('')}</ul></div>`;
  return `<div class="screen app-surface--player"><div class="player">
    <div class="player__video" style="${art(item.color)}">
      <button class="play-glyph" data-action="play-toggle">${p.playing ? '⏸' : '▶'}</button>
      <h2>${item.name}</h2>
      <small>${isLive ? 'LIVE · ' + item.now : item.desc}</small>
      ${!p.playing ? '<span class="pill">PAUSED</span>' : ''}
    </div>
    <div class="player__top">
      <button class="back-link" data-action="back">‹ Back</button>
      <span class="player__live">${isLive ? '● LIVE' : 'VOD'}</span>
      <span style="margin-left:auto;font-size:11px;color:var(--text-2)">${item.lang || 'Hindi'} · ${item.badge || 'SD'}</span>
    </div>
    <div class="player__bottom">
      <div class="player__now">${isLive ? 'Now: ' + item.now : item.name}</div>
      <div class="player__next">${isLive ? 'Next: ' + item.next : (item.genre || '') + ' · ' + (item.year || '')}</div>
      <div class="player__seek"><i style="width:${isLive ? item.progress : (item.progress || 25)}%"></i></div>
      <div class="player__controls">
        <button class="pctrl" data-action="play-toggle">${p.playing ? '⏸ Pause' : '▶ Play'}</button>
        <button class="pctrl" data-action="channel" data-d="-1">▼ Prev channel</button>
        <button class="pctrl" data-action="channel" data-d="1">▲ Next channel</button>
        <button class="pctrl" data-action="channel-list">☰ Channel list</button>
        <button class="pctrl" data-action="panel" data-panel="tracks">🔊 Audio / Subtitles</button>
        <button class="pctrl" data-action="panel" data-panel="stats">ⓘ Stats</button>
        <button class="pctrl" data-action="toast" data-message="Aspect ratio: ${state.settings.aspect}">⛶ ${state.settings.aspect}</button>
        <button class="pctrl" data-action="toast" data-message="Frame rate matched to 50 Hz">50 Hz AFR</button>
      </div>
      <div class="player__hint">D-pad: OK pause · ▲▼ channels · numeric entry · GREEN tracks · YELLOW info · BLUE stats · BACK close</div>
    </div>
    ${overlay}
  </div></div>`;
}
function welcomeScreen() {
  return `<div class="welcome">
    <div class="welcome__logo">INDIAN IPTV</div>
    <h1>Welcome</h1>
    <p>A lightweight IPTV player for your Formuler Z10 Pro Max. Ready to watch in three steps.</p>
    <div class="steps">
      <div class="step"><b>STEP 1</b><strong>Your Source</strong><small>M3U or Xtream credentials from your provider. iptv-org India playlist pre-filled.</small></div>
      <div class="step"><b>STEP 2</b><strong>Enter Once</strong><small>The app remembers your details and refreshes automatically.</small></div>
      <div class="step"><b>STEP 3</b><strong>Watch</strong><small>TV, movies and series in one list, fully remote-controllable.</small></div>
    </div>
    <button class="btn btn--accent" data-action="settings">Set Source ›</button>
    <p class="section-sub" style="margin-top:18px">Tip: a USB keyboard saves a lot of remote typing — or scan the QR code in Settings.</p>
  </div>`;
}
function pinScreen() {
  return `<div class="welcome">
    <h1>Enter PIN</h1><p>A PIN is required to open Settings from a kids profile.</p>
    <div class="pin-dots"><i></i><i></i><i></i><i></i></div>
    <div class="pinpad">${[1,2,3,4,5,6,7,8,9,'Cancel',0,'⌫'].map(k => `<button data-action="pin-key" data-key="${k}">${k}</button>`).join('')}</div>
    <button class="pill" data-action="back">Back</button>
  </div>`;
}

/* ------------------------------- Router ------------------------------- */
const screens = {
  home: homeScreen,
  movies: () => { state.type = 'MOVIES'; return homeScreen(); },
  series: () => { state.type = 'SERIES'; return homeScreen(); },
  highlight: highlightScreen,
  categories: categoriesScreen,
  guide: guideScreen,
  search: searchScreen,
  'movie-detail': movieDetailScreen,
  'series-detail': seriesDetailScreen,
  settings: settingsScreen,
  profiles: profilesScreen,
  picker: pickerScreen,
  player: playerScreen,
  welcome: welcomeScreen,
  pin: pinScreen,
};
function render() {
  $screen.innerHTML = (screens[state.screen] || homeScreen)();
  $screen.classList.toggle('app-surface--player', state.screen === 'player');
  $tabs.querySelectorAll('.preview-tab').forEach(b => b.classList.toggle('is-active', b.dataset.screen === state.screen));
  if (state.screen === 'search') {
    const input = document.getElementById('searchInput');
    if (input && !input.dataset.bound) {
      input.dataset.bound = '1';
      input.addEventListener('input', e => { state.search = e.target.value; render(); const el = document.getElementById('searchInput'); if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); } });
    }
  }
}

/* ---------------------------- Interactions ---------------------------- */
$screen.addEventListener('click', (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  const a = el.dataset.action;
  if (a === 'open') { openItem(el.dataset.id); return; }
  if (a === 'type') { state.type = el.dataset.type; state.hero = 0; render(); return; }
  if (a === 'hero') { state.hero = +el.dataset.index; render(); return; }
  if (a === 'fav') { e.stopPropagation(); toggleSet('favourites', el.dataset.id, 'Favourites'); return; }
  if (a === 'watchlist') { e.stopPropagation(); toggleSet('watchlist', el.dataset.id, 'My List'); return; }
  if (a === 'hl-mode') { state.highlightMode = el.dataset.mode; render(); return; }
  if (a === 'hl-focus') { if (!el.dataset.id.startsWith('compare')) state.highlightFocus = el.dataset.id; render(); return; }
  if (a === 'home') { navigate('home'); return; }
  if (a === 'categories') { state.category = null; navigate('categories'); return; }
  if (a === 'category') { state.category = el.dataset.name; render(); return; }
  if (a === 'cat-back') { state.category = null; render(); return; }
  if (a === 'guide') { navigate('guide'); return; }
  if (a === 'guide-day') { state.guideDay = el.dataset.day; render(); return; }
  if (a === 'reminder') { toast(`Reminder set: ${el.dataset.name} on ${el.dataset.channel}`); return; }
  if (a === 'search') { navigate('search'); return; }
  if (a === 'clear-search') { state.search = ''; render(); return; }
  if (a === 'recent') { state.search = el.dataset.value; render(); return; }
  if (a === 'clear-recents') { state.recents = []; render(); return; }
  if (a === 'settings') { navigate('settings'); return; }
  if (a === 'profiles') { navigate('profiles'); return; }
  if (a === 'pick-profile') { state.profile = { name: el.dataset.name, emoji: ({Aarav:'🦊',Priya:'🌸',Rohan:'🦁',Kids:'🧒'})[el.dataset.name], color: ({Aarav:'#5b7cfa',Priya:'#e05a8a',Rohan:'#e0a13a',Kids:'#4ecb8a'})[el.dataset.name] }; toast(`Profile switched to ${el.dataset.name}`); navigate('home'); return; }
  if (a === 'back') { back(); return; }
  if (a === 'season') { state.season = +el.dataset.n; render(); return; }
  if (a === 'play-episode') { toast(`Playing ${byId(el.dataset.id).name} · S${state.season}:E2`); navigate('player', { id: el.dataset.id }); return; }
  if (a === 'toggle') { state.settings[el.dataset.key] = !state.settings[el.dataset.key]; render(); return; }
  if (a === 'source') { state.settings.sourceType = el.dataset.value; render(); return; }
  if (a === 'aspect') { state.settings.aspect = el.dataset.value; render(); return; }
  if (a === 'audio-lang') { state.settings.audioLang = el.dataset.value; render(); return; }
  if (a === 'hour') { state.settings.refreshHour = (state.settings.refreshHour + +el.dataset.d + 24) % 24; render(); return; }
  if (a === 'play-toggle') { state.player.playing = !state.player.playing; render(); return; }
  if (a === 'channel') { const i = tv.findIndex(c => c.id === state.player.id); state.player.id = tv[(i + +el.dataset.d + tv.length) % tv.length].id; toast('Switched to ' + byId(state.player.id).name); render(); return; }
  if (a === 'play-channel') { state.player.id = el.dataset.id; state.player.listOpen = false; state.player.panel = null; render(); return; }
  if (a === 'channel-list') { state.player.listOpen = !state.player.listOpen; state.player.panel = null; render(); return; }
  if (a === 'panel') { state.player.panel = state.player.panel === el.dataset.panel ? null : el.dataset.panel; state.player.listOpen = false; render(); return; }
  if (a === 'close-panel') { state.player.panel = null; state.player.listOpen = false; render(); return; }
  if (a === 'sound') { state.settings.trailers = !state.settings.trailers; toast(state.settings.trailers ? 'Trailer sound on' : 'Trailer muted'); render(); return; }
  if (a === 'add-playlist') { toast('Another playlist URL added'); return; }
  if (a === 'toast') { toast(el.dataset.message); return; }
  if (a === 'pin-key') { toast(el.dataset.key === 'Cancel' ? 'PIN entry cancelled' : 'PIN digit: ' + el.dataset.key); return; }
});

$tabs.innerHTML = PREVIEW_SCREENS.map(s => `<button class="preview-tab" data-screen="${s.id}">${s.label}</button>`).join('');
$tabs.addEventListener('click', e => {
  const b = e.target.closest('.preview-tab');
  if (!b) return;
  const id = b.dataset.screen;
  if (id === 'movies' || id === 'series') { state.type = id.toUpperCase(); navigate('home'); state.screen = id; }
  else navigate(id);
  render();
});

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && state.screen !== 'home') back();
});

render();
