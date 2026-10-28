/**
 * The proxy rules, as the bundled sandbox ships them, so the demo on the page
 * answers the way Squid would: the blocklist is evaluated first, then the three
 * allowlist sections, and anything left over is denied.
 *
 * A leading dot matches the domain and every subdomain, exactly as dstdomain does.
 */

/** From proxy/blocked-domains.txt. */
export const blocked = [
	'.webhook.site',
	'.pipedream.com',
	'.requestbin.com',
	'.requestbin.net',
	'.hookbin.com',
	'.beeceptor.com',
	'.requestcatcher.com',
	'.pastebin.com',
	'.paste.ee',
	'.hastebin.com',
	'.dpaste.org',
	'.pastie.org',
	'.ghostbin.com',
	'.rentry.co',
	'.paste.mozilla.org',
	'.transfer.sh',
	'.file.io',
	'.0x0.st',
	'.catbox.moe',
	'.temp.sh',
	'.filedrop.io',
	'.gofile.io',
	'.tempsend.com',
	'.sendgb.com',
	'.ngrok.io',
	'.ngrok-free.app',
	'.localtunnel.me',
	'.serveo.net',
	'.localhost.run',
	'.bore.digital',
	'.telebit.cloud',
	'.loca.lt',
	'.ipinfo.io',
	'.ifconfig.me',
	'.checkip.amazonaws.com',
	'.icanhazip.com',
	'.ipify.org',
	'.wtfismyip.com',
	'.ipecho.net',
	'.myexternalip.com'
];

/** The three allowlist sections: the profile's, the agent's, and your own additions. */
export const allowed = {
	/** profiles/default/profile.json — a profile pack adds what your organisation needs. */
	profile: ['registry.npmjs.org'],
	/** agents/<id>/agent.json — what the configured agents need, not editable by hand. */
	agent: [
		'api.anthropic.com',
		'claude.ai',
		'console.anthropic.com',
		'platform.claude.com',
		'statsig.anthropic.com',
		'models.opencode.ai',
		'opencode.ai',
		'models.dev',
		'openrouter.ai'
	],
	/** Added on this machine with ./setup.sh domain add — these are only an example. */
	user: ['github.com', 'deb.debian.org', 'repo.maven.apache.org']
};

/** Hostnames that belong to a gateway instance, from the gateway manifest. */
export const gatewaySuffix = '.gateway.local';

/** @param {string} raw */
export function normalise(raw) {
	let host = (raw || '').trim().toLowerCase();
	host = host.replace(/^[a-z][a-z0-9+.-]*:\/\//, '');
	host = host.split('/')[0].split('?')[0];
	host = host.replace(/:\d+$/, '').replace(/\.$/, '');
	return host;
}

/**
 * @param {string} host
 * @param {string} entry a bare hostname, or a leading-dot suffix
 */
function matches(host, entry) {
	if (entry.startsWith('.')) {
		const bare = entry.slice(1);
		return host === bare || host.endsWith(entry);
	}
	return host === entry || host.endsWith('.' + entry);
}

/**
 * @param {string} raw
 * @returns {{host: string, verdict: 'blocked'|'allowed'|'gateway'|'denied'|'empty', section?: 'profile'|'agent'|'user', entry?: string}}
 */
export function classify(raw) {
	const host = normalise(raw);
	if (!host) return { host, verdict: 'empty' };
	if (host.endsWith(gatewaySuffix) || host === gatewaySuffix.slice(1)) {
		return { host, verdict: 'gateway' };
	}
	const hit = blocked.find((entry) => matches(host, entry));
	if (hit) return { host, verdict: 'blocked', entry: hit };
	for (const section of /** @type {const} */ (['profile', 'agent', 'user'])) {
		const allow = allowed[section].find((entry) => matches(host, entry));
		if (allow) return { host, verdict: 'allowed', section, entry: allow };
	}
	return { host, verdict: 'denied' };
}

export const examples = [
	'registry.npmjs.org',
	'api.anthropic.com',
	'webhook.site',
	'files.pipedream.com',
	'wiki.gateway.local',
	'metrics.example.com'
];
