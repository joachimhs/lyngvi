<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import { classify, examples, allowed } from '#lib/egress.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
	import Shot from './Shot.svelte';

	let query = $state('registry.npmjs.org');
	let result = $derived(classify(query));

	let reason = $derived.by(() => {
		const r = i18n.t.egress.reasons;
		switch (result.verdict) {
			case 'blocked':
				return r.blocked;
			case 'gateway':
				return r.gateway;
			case 'allowed':
				return result.section === 'profile'
					? r.allowedProfile
					: result.section === 'agent'
						? r.allowedAgent
						: r.allowedUser;
			default:
				return r.denied;
		}
	});
</script>

<section id="egress" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.egress.kicker}
			title={i18n.t.egress.title}
			lead={i18n.t.egress.lead}
		/>

		<Reveal>
			<div class="demo frame">
				<div class="demo-inner">
					<div class="field">
						<label class="visually-hidden" for="host">{i18n.t.egress.inputLabel}</label>
						<span class="scheme mono" aria-hidden="true">https://</span>
						<input
							id="host"
							class="mono"
							type="text"
							autocomplete="off"
							spellcheck="false"
							placeholder={i18n.t.egress.placeholder}
							bind:value={query}
						/>
					</div>

					<div class="examples">
						<span class="label">{i18n.t.egress.examples}</span>
						{#each examples as example (example)}
							<button class="chip" type="button" onclick={() => (query = example)}>{example}</button
							>
						{/each}
					</div>

					{#if result.verdict !== 'empty'}
						<div class="verdict {result.verdict}" aria-live="polite">
							<div class="head">
								<span class="icon" aria-hidden="true">
									{#if result.verdict === 'allowed'}
										<svg
											viewBox="0 0 16 16"
											width="15"
											height="15"
											fill="none"
											stroke="currentColor"
											stroke-width="2.2"
											stroke-linecap="round"
											stroke-linejoin="round"><path d="M3 8.5 6.5 12 13 4.5" /></svg
										>
									{:else if result.verdict === 'gateway'}
										<svg
											viewBox="0 0 16 16"
											width="15"
											height="15"
											fill="none"
											stroke="currentColor"
											stroke-width="2"
											stroke-linecap="round"
											stroke-linejoin="round"><path d="M2 8h12M9 3l5 5-5 5" /></svg
										>
									{:else}
										<svg
											viewBox="0 0 16 16"
											width="15"
											height="15"
											fill="none"
											stroke="currentColor"
											stroke-width="2.2"
											stroke-linecap="round"><path d="M4 4l8 8M12 4l-8 8" /></svg
										>
									{/if}
								</span>
								<span class="text">{i18n.t.egress.verdicts[result.verdict]}</span>
								{#if result.entry}
									<code class="entry">{result.entry}</code>
								{/if}
								{#if result.section}
									<span class="section-chip">{i18n.t.egress.sections[result.section]}</span>
								{/if}
							</div>
							<p class="reason">{reason}</p>
						</div>
					{/if}

					<div class="cmd">
						<span class="label">{i18n.t.egress.cmdNote}</span>
						<code>./setup.sh domain add {result.host || '<host>'}</code>
						<code>./setup.sh domain blocked --follow</code>
					</div>
				</div>
			</div>
		</Reveal>

		<div class="lists">
			<Reveal delay={80}>
				<article class="list">
					<h3>{i18n.t.egress.listTitle}</h3>
					<ul>
						{#each i18n.t.egress.categories as category (category.name)}
							<li>
								<span class="cat">{category.name}</span>
								<span class="hosts mono">{category.hosts}</span>
							</li>
						{/each}
					</ul>
				</article>
			</Reveal>

			<Reveal delay={140}>
				<article class="list sections">
					<h3>allowed-domains.txt</h3>
					<ul>
						<li>
							<span class="cat">{i18n.t.egress.sections.profile}</span>
							<span class="hosts mono">{allowed.profile.join(' · ')}</span>
						</li>
						<li>
							<span class="cat">{i18n.t.egress.sections.agent}</span>
							<span class="hosts mono">{allowed.agent.slice(0, 6).join(' · ')} …</span>
						</li>
						<li>
							<span class="cat">{i18n.t.egress.sections.user}</span>
							<span class="hosts mono">{allowed.user.join(' · ')}</span>
						</li>
					</ul>
				</article>
			</Reveal>
		</div>

		<Reveal delay={60}>
			<div class="figure">
				<Shot
					name="x-blocked"
					width={1200}
					height={228}
					title="Lyngvi — Traffic"
					alt={i18n.t.egress.figure.alt}
					caption={i18n.t.egress.figure.caption}
				/>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.figure {
		margin-top: 2rem;
	}

	.demo-inner {
		padding: 1.5rem 1.4rem;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.7), rgba(5, 11, 20, 0.4));
	}

	/* The address bar: the one input on the page, so it gets the full treatment. */
	.field {
		display: flex;
		align-items: center;
		gap: 0.1rem;
		padding: 0.6rem 1rem;
		border: 1px solid var(--line-strong);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.85);
		transition:
			border-color 0.15s ease,
			box-shadow 0.15s ease;
	}

	.field:focus-within {
		border-color: var(--cyan);
		box-shadow: var(--glow);
	}

	.scheme {
		color: var(--faint);
		font-size: 0.95rem;
	}

	input {
		flex: 1;
		min-width: 0;
		border: 0;
		background: transparent;
		color: var(--text);
		font-size: 1.05rem;
		padding: 0.25rem 0;
	}

	input:focus {
		outline: none;
	}

	.examples {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.4rem;
		margin-top: 1rem;
	}

	.label {
		font-size: 0.78rem;
		color: var(--faint);
	}

	button.chip {
		cursor: pointer;
		transition:
			border-color 0.15s ease,
			color 0.15s ease;
	}

	button.chip:hover {
		border-color: var(--cyan);
		color: var(--text);
	}

	.verdict {
		margin-top: 1.2rem;
		padding: 1rem 1.1rem;
		border: 1px solid var(--line);
		border-left-width: 3px;
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.55);
	}

	.verdict.allowed {
		border-left-color: var(--ok);
	}

	.verdict.gateway {
		border-left-color: var(--cyan);
	}

	.verdict.blocked,
	.verdict.denied {
		border-left-color: var(--bad);
	}

	.head {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem;
	}

	.icon {
		display: grid;
		place-items: center;
		width: 1.6rem;
		height: 1.6rem;
		border-radius: var(--r-sm);
	}

	.allowed .icon {
		background: var(--ok-soft);
		color: var(--ok);
	}

	.gateway .icon {
		background: var(--cyan-soft);
		color: var(--cyan);
	}

	.blocked .icon,
	.denied .icon {
		background: var(--bad-soft);
		color: var(--bad);
	}

	.text {
		font-family: var(--mono);
		font-size: 0.78rem;
		font-weight: 500;
		letter-spacing: 0.1em;
		text-transform: uppercase;
	}

	.allowed .text {
		color: var(--ok);
	}

	.gateway .text {
		color: var(--cyan);
	}

	.blocked .text,
	.denied .text {
		color: var(--bad);
	}

	.entry {
		padding: 0.1rem 0.45rem;
		border-radius: var(--r-sm);
		background: rgba(142, 190, 222, 0.1);
		color: var(--dim);
		font-size: 0.76rem;
	}

	.section-chip {
		padding: 0.1rem 0.5rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		font-family: var(--mono);
		font-size: 0.68rem;
		color: var(--faint);
	}

	.reason {
		margin-top: 0.7rem;
		color: var(--dim);
		font-size: 0.9rem;
		max-width: 70ch;
	}

	.cmd {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.6rem;
		margin-top: 1.2rem;
		padding-top: 1.1rem;
		border-top: 1px solid var(--line);
	}

	.cmd code {
		padding: 0.25rem 0.6rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.7);
		color: var(--dim);
		font-size: 0.76rem;
	}

	.lists {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(290px, 1fr));
		gap: 0 2.5rem;
		margin-top: 2.5rem;
	}

	.lists :global(.reveal) {
		height: 100%;
	}

	.list {
		height: 100%;
	}

	.list h3 {
		font-family: var(--mono);
		font-size: 0.7rem;
		font-weight: 500;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--faint);
	}

	.list ul {
		margin: 0.9rem 0 0;
		padding: 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.list li {
		display: grid;
		gap: 0.15rem;
		padding: 0.6rem 0;
		border-bottom: 1px solid var(--line);
	}

	.cat {
		font-size: 0.88rem;
		font-weight: 600;
	}

	.sections .cat {
		color: var(--cyan);
	}

	.hosts {
		font-size: 0.74rem;
		color: var(--faint);
		word-break: break-word;
	}
</style>
