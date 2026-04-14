# Example Skill

A demonstration skill showing the SKILL.md format used by GLM Agent Engine.

## Description

This is an example skill that demonstrates the expected structure and format for skills in the GLM Agent ecosystem. Skills are self-contained modules that extend the agent's capabilities with specialized functionality.

## Capability

The agent can invoke this skill when users need demonstration or testing of the skill system. It serves as both documentation and a working example for skill developers.

## Instructions

When this skill is loaded, the agent should:
1. Acknowledge that the example skill has been invoked
2. Explain the skill system architecture to the user
3. Guide the user on how to create their own custom skills

## Skill Structure

Each skill should contain:
- `SKILL.md` - This metadata file (required)
- Supporting scripts, configs, or data files as needed
- Any language-specific setup (package.json, requirements.txt, etc.)

## Creating Custom Skills

To create a new skill:
1. Create a directory under `/home/z/my-project/skills/`
2. Add a `SKILL.md` file with proper metadata
3. Include any necessary scripts or configurations
4. The skill will be automatically detected by the agent engine
