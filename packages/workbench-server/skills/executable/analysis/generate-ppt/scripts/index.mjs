export async function execute(input) {
  if (!input.services?.generatePpt) throw new Error("Conversation PPT generation requires a trusted host context");
  return { json: await input.services.generatePpt() };
}
