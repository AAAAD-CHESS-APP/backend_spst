package com.aaaadchess.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Scanner;

@SpringBootTest
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void runStockfish() {
		String stockfishPath = System.getProperty("user.home") + "/.local/bin/stockfish";

		try {
			ProcessBuilder pb = new ProcessBuilder(stockfishPath, "--help");
			Process process = pb.start();

			try (Scanner s = new Scanner(process.getInputStream()).useDelimiter("\\A")) {
				String output = s.hasNext() ? s.next() : "";
				System.out.println("Stockfish Output:\n" + output);
			}

			try (Scanner s = new Scanner(process.getErrorStream()).useDelimiter("\\A")) {
				String error = s.hasNext() ? s.next() : "No error";
				System.err.println("Stockfish Error:\n" + error);
			}

			int exitCode = process.waitFor();
			System.out.println("Stockfish exited with code: " + exitCode);

		} catch (IOException | InterruptedException e) {
			System.err.println("Error running Stockfish: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
