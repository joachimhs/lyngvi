<script>
	import Nav from '#lib/components/Nav.svelte';
	import Hero from '#lib/components/Hero.svelte';
	import Premises from '#lib/components/Premises.svelte';
	import Architecture from '#lib/components/Architecture.svelte';
	import Screens from '#lib/components/Screens.svelte';
	import SandboxView from '#lib/components/SandboxView.svelte';
	import Egress from '#lib/components/Egress.svelte';
	import Delivery from '#lib/components/Delivery.svelte';
	import Gateway from '#lib/components/Gateway.svelte';
	import Features from '#lib/components/Features.svelte';
	import Hardening from '#lib/components/Hardening.svelte';
	import Install from '#lib/components/Install.svelte';
	import Footer from '#lib/components/Footer.svelte';
</script>

<Nav />

<main>
	<Hero />
	<Screens />
	<Premises />
	<Architecture />
	<SandboxView />
	<Egress />
	<Delivery />
	<Gateway />
	<Features />
	<Hardening />
	<Install />
</main>

<Footer />
