const readline = require("readline");

const rl = readline.createInterface({ input: process.stdin });

function send(id, result) {
	process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}

function sendError(id, code, message) {
	process.stdout.write(JSON.stringify({
		jsonrpc: "2.0",
		id,
		error: { code, message },
	}) + "\n");
}

rl.on("line", (line) => {
	let req;
	try {
		req = JSON.parse(line);
	} catch (error) {
		return;
	}

	const { id, method, params } = req;
	if (method === "notifications/initialized") return;

	if (method === "initialize") {
		send(id, {
			protocolVersion: "2025-06-18",
			capabilities: { tools: {} },
			serverInfo: { name: "demo-mcp", version: "1.0.0" },
		});
		return;
	}

	if (method === "tools/list") {
		send(id, {
			tools: [{
				name: "echo",
				description: "Echo a message from demo MCP server.",
				inputSchema: {
					type: "object",
					properties: {
						text: { type: "string" },
					},
					required: ["text"],
				},
			}],
		});
		return;
	}

	if (method === "tools/call") {
		const text = params?.arguments?.text ?? "";
		send(id, {
			content: [{ type: "text", text: `MCP echo: ${text}` }],
			structuredContent: { text },
			isError: false,
		});
		return;
	}

	sendError(id, -32601, `unknown method: ${method}`);
});
